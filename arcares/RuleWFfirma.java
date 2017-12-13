package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.QueryManager;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.legalbus.client.LegalBusClient;
import it.infocert.legalbus.wsimport.Attribute;
import it.infocert.legalbus.wsimport.Document;
import it.infocert.legalbus.wsimport.Error;
import it.infocert.legalbus.wsimport.ExecuteProcess;
import it.infocert.legalbus.wsimport.ExecuteProcessResponse;
import it.infocert.legalbus.wsimport.LbHubImportService;
import it.infocert.legalbus.wsimport.StatusResponse;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.util.*;
import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

/**
 *
 * @author
 */
public class RuleWFfirma implements WrRuleClassBody {

    WorkSession ws;
    Logger logger;
    Entity entity;
    InputStream content;
    String contentName;
    String deserroreFirma;

    private final String updateDBArcaresRuleName = "UpdateDBArcares";
    
    void setParameters(Map parameters) {
        ws = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    public Object run(Map parameters) {
    	
    	deserroreFirma = "";
        String errorMessage = "";
        
        setParameters(parameters);
        HashMap retMap = new HashMap();

        logger.info("RuleWFfirma: Start");

        String idFlusso = (String) entity.getPropertyValue("idFlusso");
        String idDocumento = (String) entity.getPropertyValue("idDocumento");
        String path = (String) entity.getPropertyValue("docPath");
        
        boolean wasSkippingPolicies = ws.isSkippingPolicies();
        try {
           ws.setSkipPolicies(true);
           Boolean flagMarca = (Boolean) entity.getPropertyValue("flagMarca");
           Boolean flagConserva = (Boolean) entity.getPropertyValue("flagConserva");
           Boolean flagInviaMail = (Boolean) entity.getPropertyValue("flagInviaMail");
             
           boolean sign = sign(path,flagMarca);
           String statoFirma = "2";
           if(!sign){
               statoFirma = "3";
               flagConserva = false;
               flagInviaMail = false;
           }
           //aggiorno lo stato Firmato / Marcato. FIRMA OK -> MARCA OK . FIRMA KO -> MARCA KO
           String update = "UPDATE WRDC_DETAIL "
                   + "SET COD_STATO_MARCA = '" + statoFirma + "' , COD_STATO_FIRM = '" + statoFirma + "', COD_ERRORE_FIRM = '', DES_ERRORE_FIRM = '" + deserroreFirma + "' "
                   + "WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";

           HashMap paramsUpdate = new HashMap();
           paramsUpdate.put("workSession", ws);
           paramsUpdate.put("logger", logger);
           paramsUpdate.put("query", update);
           logger.info("calling externalRule " + updateDBArcaresRuleName);
           WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);

           entity.setProperty("statoFirmato", statoFirma);
           logger.info("RuleWFfirma: Documento firmato correttamente");
            
           retMap.put("conserva", flagConserva);
           retMap.put("invio", flagInviaMail);
            
           entity.persist();
           ws.save();
            
        } catch (Exception e) {

            //aggiorno il campo stato in errore
            String updateError = "UPDATE WRDC_DETAIL "
                    + "SET COD_STATO = '4', COD_STATO_MARCA = '3' , COD_STATO_FIRM = '3', DES_ERRORE_FIRM = 'Errore Tecnico Firma / Marcatura'  "
                    + "WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
            try {
                HashMap paramsUpdate = new HashMap();
                paramsUpdate.put("workSession", ws);
                paramsUpdate.put("logger", logger);
                paramsUpdate.put("query", updateError);
                logger.info("calling externalRule " + updateDBArcaresRuleName);
                WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
            } catch (Exception e1) {
                logger.error("error: {}", ExceptionUtils.getMessage(e));
                logger.debug(ExceptionUtils.getStackTrace(e));
                throw new RuntimeException("ERRORE in RuleWFfirma (ATTENZIONE STATO DELLA FIRMA + MARCA NON AGGIORNATO IN ERRORE NEL DB ARCARES!!!!): " + e.getMessage(), e);
            }
            entity.setProperty("statoFirmato", "3");
            entity.setProperty("stato", "4");
            try {
                entity.persist();
                ws.save();
            } catch (Exception e1) {
                logger.error("error: {}", ExceptionUtils.getMessage(e));
                logger.error(ExceptionUtils.getStackTrace(e));
                throw new RuntimeException("ERRORE in RuleWFfirma (ATTENZIONE STATO DELLA FIRMA + MARCA NON AGGIORNATO IN ERRORE SU WR!!!!): " + e.getMessage(), e);
            }
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.error(ExceptionUtils.getStackTrace(e));
            
        } finally {
            ws.setSkipPolicies(wasSkippingPolicies);

            logger.info("RuleWFfirma: End");
        }

        return retMap;
    }
   
    private boolean sign(String path , Boolean marcatura) {
        boolean sign = false;
        File tmpDataFile = null;
        try {
            File workDir = getWorkingDir();

            String queryString = "//element(*,opzioniFirma)";
            String codFirmaDigi = (String) entity.getPropertyValue("codFirmaDigi");
            if(codFirmaDigi != null && !codFirmaDigi.isEmpty()){
                queryString += "[(fn:lower-case(@user) = '" + codFirmaDigi.toLowerCase() + "')]";
            }
            StringBuilder optSign = new StringBuilder().append("/jcr:root").append(queryString);
            QueryManager qm = ws.getQueryManager();
            List<Entity> optionList = qm.executeQuery(optSign.toString()).getResults();
            Entity option = null;

            if (optionList.size() == 1) {
                option = (Entity) optionList.get(0);
            } else {
                throw new Exception("Errore nel recupero dell'entita' opzioniFirma");
            }

            String url = (String) option.getPropertyValue("url");
            logger.info("url {}", url);
            String username = (String) option.getPropertyValue("user");
            logger.info("username {}", username);
            String userpwd = (String) option.getPropertyValue("password");
            logger.info("userpwd {}", userpwd);
            String idAnagrafica = (String) option.getPropertyValue("idAnagrafica");
            logger.info("idAnagrafica {}", idAnagrafica);
            String idProcesso = (String) option.getPropertyValue("idProcesso");
            logger.info("idProcesso {}", idProcesso);
            String signDomain = (String) option.getPropertyValue("signDomain");
            logger.info("signDomain {}", signDomain);
            String signPin = (String) option.getPropertyValue("signPIN");
            logger.info("signPin {}", signPin);
            String signAlias = (String) option.getPropertyValue("signAlias");
            logger.info("signAlias {}", signAlias);

            List resourceList = entity.getStructuredProperty("resources").getRecords();
            for (int j = 0; j < resourceList.size(); j++) {
                StructuredPropertyRecord record = (StructuredPropertyRecord) resourceList.get(j);
                String curDocFileName = (String) record.getPropertyValue("originalFilename");

                tmpDataFile = new File(workDir, curDocFileName);
                logger.info("dataFile #{}: {}", j, tmpDataFile.getPath());

                InputStream inStream = (InputStream) record.getPropertyValue("data");
                writeStreamToFile(inStream, tmpDataFile);

                LegalBusClient client = new LegalBusClient();
                LbHubImportService service = client.bindToWebServices(url, username, userpwd);

                ExecuteProcess executeProcess = new ExecuteProcess();

                logger.info("TestFirmaDigitale: Connection OK.");

                // Lista attributi
                List<Attribute> commonAttribute = executeProcess.getCommonAttribute();
                // id-anagrafica
                Attribute idAnagraficaAtt = new Attribute();
                idAnagraficaAtt.setName("id-anagrafica");
                idAnagraficaAtt.setValue(idAnagrafica);
                commonAttribute.add(idAnagraficaAtt);

                // id-processo
                Attribute idProcessoAtt = new Attribute();
                idProcessoAtt.setName("id-processo");
                idProcessoAtt.setValue(idProcesso);
                commonAttribute.add(idProcessoAtt);

                Attribute signDomainAtt = new Attribute();
                signDomainAtt.setName("sign-domain");
                signDomainAtt.setValue(signDomain);
                commonAttribute.add(signDomainAtt);

                Attribute signPinAtt = new Attribute();
                signPinAtt.setName("sign-pin");
                signPinAtt.setValue(signPin);
                commonAttribute.add(signPinAtt);

                Attribute signAliasAtt = new Attribute();
                signAliasAtt.setName("sign-alias");
                signAliasAtt.setValue(signAlias);
                commonAttribute.add(signAliasAtt);

                Attribute signType = new Attribute();
                signType.setName("sign-type");
                signType.setValue("PADES");
                commonAttribute.add(signType);

                /*Attribute signTimeStamp = new Attribute();
                signTimeStamp.setName("sign-timestamp");
                signTimeStamp.setValue("false");
                commonAttribute.add(signTimeStamp);
                */

                if (marcatura) {
                	logger.info("Marcatura Temporale Abilitata !!! ");
                	Attribute signMarcatura = new Attribute();
                	signMarcatura.setName("sign-isMarcaTemporaleEnabled");
                	signMarcatura.setValue("true");
                    commonAttribute.add(signMarcatura);       
                }
                            
                
                List<Document> docs = executeProcess.getDocument();
                Document doc = new Document();
                doc.setName("Il mio documento");

                DataSource fds = new FileDataSource(tmpDataFile);
                DataHandler dh = new DataHandler(fds);
                doc.setContent(dh);
                doc.setContentType(dh.getContentType());
                logger.info("RuleWFfirma: Inserito Documento OK.");
                List<Attribute> attributeDoc = doc.getAttribute();

                Attribute esternalReference = new Attribute();
                esternalReference.setName("esternal-reference");
                esternalReference.setValue("external_ref_1");
                attributeDoc.add(esternalReference);

                Attribute idTipologia = new Attribute();
                idTipologia.setName("id-tipologia");
                idTipologia.setValue("BaseDocument");
                attributeDoc.add(idTipologia);

                docs.add(doc);

                ExecuteProcessResponse epResponse = service.executeProcess(executeProcess);
                StatusResponse response = epResponse.getReturn();
                List<Document> retDocument = response.getDocument();
                if (retDocument == null || retDocument.isEmpty()) {
                    List<Error> Errors = response.getError();
                    for (Error error : Errors) {
                        logger.error("Errore nella firma / marca remota: " + error.getErrorCode() + " - " + error.getErrorMessage());
                        if (error != null && error.getErrorMessage() != null && !error.getErrorMessage().equalsIgnoreCase("")) {
                        	int causedByindex = error.getErrorMessage().lastIndexOf("Caused by:");                       	
                            deserroreFirma = (causedByindex > 0 ) ?  ((error.getErrorCode() + " - " + error.getErrorMessage().substring(0, causedByindex))) :
                            		(error.getErrorCode() + " - " + error.getErrorMessage());
                            deserroreFirma = (deserroreFirma.length() > 149 ) ? deserroreFirma.substring(0,149) : deserroreFirma;
                        }
                        throw new Exception("Errore nella firma / marca remota");
                    }
                } else {
                    for (Document document : retDocument) {
                        logger.info("document {}", document);
                        DataHandler content = document.getContent();
                        InputStream inputStream = content.getInputStream();
                        
                        File file = new File(tmpDataFile.getParentFile().getAbsolutePath() + "/" + tmpDataFile.getName());
                      
                        OutputStream outputStream = new FileOutputStream(file);
                        IOUtils.copy(inputStream, outputStream);
                        outputStream.close();
                        
                        
                         if (path != null && !path.isEmpty()) {
                            File docArcares = new File(path);
                            try
                            {    logger.info("rule firma: salva su arcares  : {}", docArcares.toPath());
                                 Files.copy(file.toPath(), docArcares.toPath(), REPLACE_EXISTING);
                                 
                            } catch(Exception ex)
                            {
                                // In caso di errore di copia perde l'originale quindi lo salviamo in un path 
                                File docArcaresSaved = new File(path+".saved.pdf");
                                Files.copy(file.toPath(), docArcaresSaved.toPath());
                                logger.error("rule firma: error copy file to Arcares repository saved original in :saved.pdf: {}", ExceptionUtils.getMessage(ex));
                                throw ex;
                            }
                        }   
                        /*
                        if(path != null && !path.isEmpty()){
                            File docArcares = new File(path);
                            Files.copy(file.toPath(), docArcares.toPath(), REPLACE_EXISTING);
                        }*/
                        
                      

                        record.setProperty("data", new FileInputStream(file));
                        record.setProperty("fileSize", FileUtils.sizeOf(file));

                        try {
                            file.delete();
                        } catch (Exception e) {
                            logger.error("error: {}", ExceptionUtils.getMessage(e));
                            logger.error(ExceptionUtils.getStackTrace(e));
                        }
                    }
                }
                
            }
            sign = true;
        } catch (Exception ex) {
            logger.error("Error copying stream ...", ex);
        } finally {
            try {
               // tmpDataFile.delete();
            } catch (Exception e) {
                logger.error("error: {}", ExceptionUtils.getMessage(e));
                logger.error(ExceptionUtils.getStackTrace(e));
            }
        }
        return sign;
    }

    private File getWorkingDir() {
        Calendar ora = Calendar.getInstance();
        File temporaryDir = new File(System.getProperty("java.io.tmpdir"),
                "" + ora.getTimeInMillis());
        logger.info("Temporary working dir: {}", temporaryDir.getPath());
        temporaryDir.mkdirs();
        return temporaryDir;
    }

    private void writeStreamToFile(InputStream source, File dstFile) {
        OutputStream outStm = null;
        try {
            outStm = new BufferedOutputStream(new FileOutputStream(dstFile));
            IOUtils.copy(source, outStm);
        } catch (Exception ex) {
            logger.error("Error copying stream ...", ex);
        } finally {
            if (outStm != null) {
                IOUtils.closeQuietly(outStm);
            }
            IOUtils.closeQuietly(source);
        }
    }
}
