package rules.arcares;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.QueryManager;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.repository.entities.StructuredProperty;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import it.cbt.wr.api.service.repository.qualities.Resource;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.utils.ISO9075;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class RuleWFrecuperaDocumenti implements WrRuleClassBody {

    WorkSession ws; // Required
    Logger logger; // Required
    Entity entity; // Required
    
    Connection con = null;
    private Statement updateStatemant;

    private static final String WR_FOLDER_ENTITY_TYPE = "cartellaGenerica";
    private final String updateDBArcaresRuleName = "UpdateDBArcares";
    void setParameters(Map parameters) {
        ws = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    public Object run(Map parameters) {
        HashMap retMap = new HashMap();

        setParameters(parameters);

        boolean wasSkippingPolicies = ws.isSkippingPolicies();
        
        String idFlusso = null;
        try {
            ws.setSkipPolicies(true);
            // recupera opzioni di collegamento al DB di Arcares
            StringBuilder query = new StringBuilder().append("/jcr:root").append("//element(*,opzioneArcares)");
            QueryManager qm = ws.getQueryManager();
            List<Entity> list = qm.executeQuery(query.toString()).getResults();

            if (list != null && !list.isEmpty()) {
               
                idFlusso = (String) entity.getPropertyValue("idFlusso");
                String select = "SELECT * "
                        + "FROM WRDC_DETAIL AS DTL "
                        + "LEFT JOIN WRDC_INDIR AS INDIR "
                        + "ON INDIR.ID_FLUSSO = DTL.ID_FLUSSO AND INDIR.ID_DESTINAZIONE = DTL.ID_DESTINAZIONE "
                        + "WHERE DTL.COD_STATO = '0' AND DTL.ID_FLUSSO = '" + idFlusso + "'";

                con = getConnectionForArcaresDB();

                updateStatemant = con.createStatement();
                ResultSet res = updateStatemant.executeQuery(select);            
                
                while (res.next()) {
                    // fase creazione del tipo di documento
                    String tipologia = res.getString("COD_TIPO_DOC").trim();
                    String idDocumento = res.getString("ID_DOCUMENTO").trim();
                        
                    logger.info("TIPOLOGIA: '{}'", tipologia);
                    
                    Entity documento = null;
                    // Creazione Documento
                    if (tipologia.equals("ECDEBI")) { 
                        documento = ws.createNewEntity("estrattoContoDebitore", entity); 
                    } else if (tipologia.equals("ECDEBIMATY")) { 
                        documento = ws.createNewEntity("estrattoContoDebitoreMaturity", entity); 
                    } else if (tipologia.equals("FATTUCLIE")) { 
                        documento = ws.createNewEntity("fatturaVersoClientela", entity); 
                    } else if (tipologia.equals("NOTYDEBIITA")) { 
                        documento = ws.createNewEntity("notificaDebitoreItaliano", entity); 
                    } else if (tipologia.equals("ECCEDE")) {
                        documento = ws.createNewEntity("estrattoContoCedente", entity);                   
                   } else { 
                        String update = "UPDATE WRDC_DETAIL "
                                    + "SET COD_STATO = '3', COD_ERRORE = '10' , DES_ERRORE = 'Tipo Documento " + tipologia + " non riconosciuto' "
                                    + "WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
                            try {
                                HashMap paramsUpdate = new HashMap();
                                paramsUpdate.put("workSession", ws);
                                paramsUpdate.put("logger", logger);
                                paramsUpdate.put("query", update);
                                logger.info("calling externalRule " + updateDBArcaresRuleName);
                                WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
                            } catch (Exception ex) {
                                logger.error("error: {}", ExceptionUtils.getMessage(ex));
                                logger.debug(ExceptionUtils.getStackTrace(ex));
                                throw new RuntimeException("ERRORE in RuleWFrecuperaDocumenti ERRORE NEL DB ARCARES!!!!): " + ex.getMessage(), ex);
                            }
                    }

                    if (documento != null) {
                        // Imposto Documento
                        documento.setProperty("idFlusso", idFlusso);
                        documento.setProperty("idDocumento", idDocumento);
                        documento.setProperty("token", "");
                        //documento.setProperty("nProtocollo", res.getString("COD_PROTOCOL").trim());
                        //documento.setProperty("dataDocumento", res.getDate("DATA_CONT")); // Data Protocollo????
                        documento.setProperty("tipoProtocollo", "");
                        documento.setProperty("codNumId", res.getLong("COD_NUMID"));
                        documento.setProperty("codNumIdFile", res.getLong("COD_NUMID_FILE"));
                        documento.setProperty("codProcedura", res.getString("COD_PROC").trim());
                        documento.setProperty("codOutput", res.getString("COD_OUTP").trim());
                        documento.setProperty("dataSpedizione", res.getDate("DAT_DELIVERY"));
                        //documento.setProperty("codFirmaDigi", res.getString("COD_FIRMA_DIGI"));
                        
                        documento.setProperty("flagMarca", res.getString("FLG_MARCA").trim().equals("N") || res.getString("FLG_MARCA") == null ? false : true);
                        documento.setProperty("stato", "Da Conservare");
                        // Invio Mail
                        Boolean flagInviaMailPoste = res.getString("FLG_DELIVERY").trim().equals("N") || res.getString("FLG_DELIVERY") == null ? false : true;
                        String idDestinazione = res.getString("ID_CANALE_PRIM");
                        String idDestinazioneSec = res.getString("ID_CANALE_SEC");
                        
                        documento.setProperty("flagInviaMail", flagInviaMailPoste);
                        documento.setProperty("stato", "1");
                        documento.persist();
                        ws.save();
                            
                        // Se uno dei documenti prevede l'invia mail imposta il flag a true.
                        boolean isAutomatico = false;
                        if (flagInviaMailPoste) {
                            if (idDestinazione != null) {
                                idDestinazione = idDestinazione.trim();
                                if (idDestinazione.equalsIgnoreCase("EMAIL") || idDestinazione.equalsIgnoreCase("PEC")) {
                                    logger.info("RuleWFrecuperaDocumenti - Creo entity outgoingMail ...");
                                    Entity outgoingMail = ws.createNewEntity("outgoingMail", documento);
                                    outgoingMail.setProperty("name", "Email in uscita principale");
                                    outgoingMail.setProperty("principale", true);
                                    outgoingMail.persist();
                                    ws.save();
                                    isAutomatico = true;
                                }else if (idDestinazione.equalsIgnoreCase("ORDINARY_MAIL")
                                        || idDestinazione.equalsIgnoreCase("REGISTERED_MAIL")) {
                                    logger.info("RuleWFrecuperaDocumenti - Creo entity documentoPostale ...");
                                    Entity documentoPostale = ws.createNewEntity("documentoPostale", documento);
                                    documentoPostale.setProperty("name", "Documento postale principale");
                                    documentoPostale.setProperty("principale", true);
                                    documentoPostale.persist();
                                    ws.save();
                                    isAutomatico = true;
                                }
                                
                                idDestinazioneSec = idDestinazioneSec.trim();
                                if (idDestinazioneSec.equalsIgnoreCase("EMAIL") || idDestinazioneSec.equalsIgnoreCase("PEC")) {
                                    Entity outgoingMail = ws.createNewEntity("outgoingMail", documento);
                                    outgoingMail.setProperty("name", "Email in uscita secondario");
                                    outgoingMail.setProperty("principale", false);
                                    outgoingMail.persist();
                                    ws.save();
                                    isAutomatico = true;
                                }
                                if (idDestinazioneSec.equalsIgnoreCase("ORDINARY_MAIL")
                                        || idDestinazioneSec.equalsIgnoreCase("REGISTERED_MAIL")) {
                                    Entity documentoPostale = ws.createNewEntity("documentoPostale", documento);
                                    documentoPostale.setProperty("name", "Documento postale secondario");
                                    documentoPostale.setProperty("principale", false);
                                    documentoPostale.persist();
                                    ws.save();
                                    isAutomatico = true;
                                }
                                
                                logger.info("Preparazione invio documento {} ", tipologia);
                            } else {
                                documento.setProperty("statoInvio", "3");
                                String update = "UPDATE WRDC_DETAIL "
                                        + "SET COD_STATO_DLVRY = '3', "
                                        + "COD_ERRORE_DLVRY='3', "
                                        + "DES_ERRORE_DLVRY='ID_DESTINAZIONE VUOTO' "
                                        + "WHERE ID_FLUSSO = '" + idFlusso + "' "
                                        + "AND ID_DOCUMENTO = '" + idDocumento + "'";
                                HashMap paramsUpdate = new HashMap();
                                paramsUpdate.put("workSession", ws);
                                paramsUpdate.put("logger", logger);
                                paramsUpdate.put("query", update);
                                logger.info("calling externalRule " + updateDBArcaresRuleName);
                                WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
                                logger.info("Documento {} senza destinazione obbligatoria", tipologia);
                            }
                            if(!isAutomatico){
                                documento.setProperty("flagInviaMail", false);
                            }
                        }
                        // Conservazione/Archiviazione
                        Boolean flagConservazioneDoc = res.getString("FLG_ARCH").trim().equals("N") || res.getString("FLG_ARCH") == null ? false : true;
                        documento.setProperty("flagConserva", flagConservazioneDoc);

                       // Firma
                        Boolean flagFirmaDoc = res.getString("FLG_FIRM").trim().equals("N") || res.getString("FLG_FIRM") == null ? false : true;
                        documento.setProperty("flagFirma", flagFirmaDoc);

                        // Marca
                        Boolean flagMarcaDoc = res.getString("FLG_MARCA").trim().equals("N") || res.getString("FLG_MARCA") == null ? false : true;
                        documento.setProperty("flagMarca", flagMarcaDoc);

                        // Protocolla
                        Boolean flagProtocollaDoc = res.getString("FLG_PROTOCOL").trim().equals("N") || res.getString("FLG_PROTOCOL") == null ? false : true;
                        documento.setProperty("flagProtocolla", flagProtocollaDoc);
                        
                        
                        // Imposto la risorsa documento
                        String path = res.getString("DES_DOC_PATH").trim() + "/" + res.getString("DES_DOC_NAME").trim();
                        documento.setProperty("docPath", path);
                        
                        documento.persist();
                        ws.save();
                        
                        try{
                            setRisorsaByFilePath(documento, path);
                            documento.persist();
                            ws.save();    
                            
                            // Imposto sul db di arcares lo stato principale in elaborazione
                            String update = "UPDATE WRDC_DETAIL SET DAT_DELIVERY = CURRENT_DATE, ID_WR_ENTITY = '" + documento.getId() + "', COD_STATO = '1' WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + res.getString("ID_DOCUMENTO").trim() + "'";
                            //--la eseguo
                            HashMap paramsUpdate = new HashMap();
                            paramsUpdate.put("workSession", ws);
                            paramsUpdate.put("logger", logger);
                            paramsUpdate.put("query", update);
                            logger.info("calling externalRule " + updateDBArcaresRuleName);
                            WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
                        } catch (Exception e) {
                            logger.error("error: {}", ExceptionUtils.getMessage(e));
                            logger.error(ExceptionUtils.getStackTrace(e));
                            
                            String updateError = "UPDATE WRDC_DETAIL "
                                    + "SET COD_STATO = '4', DES_ERRORE = '" + e.getLocalizedMessage() + "' "
                                    + "WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
                            try {
                                HashMap paramsUpdate = new HashMap();
                                paramsUpdate.put("workSession", ws);
                                paramsUpdate.put("logger", logger);
                                paramsUpdate.put("query", updateError);
                                logger.info("calling externalRule " + updateDBArcaresRuleName);
                                WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
                            } catch (Exception ex) {
                                logger.error("error: {}", ExceptionUtils.getMessage(ex));
                                logger.debug(ExceptionUtils.getStackTrace(ex));
                                throw new RuntimeException("ERRORE in RuleWFrecuperaDocumenti sulla setRisorsaByFilePath : " + e.getMessage(), e);
                            }
                        }
                    }
                    
                     
                }
                res.close();
                
            }
        } catch (Exception e) {
            
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.error(ExceptionUtils.getStackTrace(e));
                        
            try {
                entity.persist();
                ws.save();
            } catch (Exception e1) {
                logger.error("error: {}", ExceptionUtils.getMessage(e));
                logger.error(ExceptionUtils.getStackTrace(e));
                throw new RuntimeException("ERRORE in RuleWFrecuperaDocumenti : " + e.getMessage(), e);
            }            
            
        } finally {
            ws.setSkipPolicies(wasSkippingPolicies);
            if (updateStatemant != null) {
                try {
                    updateStatemant.close();
                } catch (SQLException e) {
                    logger.error("error: {}", ExceptionUtils.getMessage(e));
                    logger.debug(ExceptionUtils.getStackTrace(e));
                }
            }
            if (con != null) {
                try {
                    con.close();
                } catch (SQLException e) {
                    logger.error("RuleWFrecuperaDocumenti Database Connection Error: {}", ExceptionUtils.getMessage(e));
                }
            }
        }
        return retMap;
    }

    private void setRisorsaByFilePath(Entity entityTarget, String path) throws FileNotFoundException {
        //ottengo la proprietà strutturata delle risorse
        StructuredProperty resources = entityTarget.getStructuredProperty(Resource.RESOURCES_PROPERTY);
        File file = new File(path);
        if(file == null){
            throw new FileNotFoundException("File " + path + " non trovato");
        }
        FileInputStream fileInputStream = new FileInputStream(file);
        StructuredPropertyRecord newRecord = resources.createRecord();
        newRecord.setProperty("fileExtension", FilenameUtils.getExtension(path));
        newRecord.setProperty("description", file.getName());
        newRecord.setProperty("data", fileInputStream);
        newRecord.setProperty("originalFilename", file.getName());

        try {
            Files.probeContentType(file.toPath());
            String tikaType = Files.probeContentType(file.toPath());
            newRecord.setProperty("mimeType", tikaType);
            resources.addRecord(newRecord);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // QUESTO METODO GENERA UNA TREE DI CONTAINER E RITORNA LA FOLDER DOVE
    // BISOGNA INSERIRE LA NUOVA ENTITY
    private Entity generateTreeInsideFolder(String strAnno, String strMese, Entity folderTarget) {
        // CREA CARTELLA ANNO
        Entity folder = generateContainerInsideFolder(strAnno, folderTarget);

        // CREA CARTELLA MESE
        folder = generateContainerInsideFolder(strMese, folder);

        return folder;
    }

    private Entity getRootFolderId() {
        List<Entity> containers = ws.getQueryManager().executeQuery("//element(*, containers)").getResults();
        Entity rootContainer = (Entity) containers.get(0);
        return rootContainer;
    }

    // FUNZIONAMENTO METODO: CREA CARTELLA SE NON ESISTE
    // folderName : nome della cartella da creare, se non esiste.
    // folder : dove creare la cartella, se non esiste
    // RESTITUISCE LA CARTELLA FOLDERTARGET
    private Entity generateContainerInsideFolder(String folderName, Entity folderTarget) {
        Entity oFolder = null;

        String folderTargetPath = ISO9075.encodePath(folderTarget.getPath());

        String containerQuery = "/jcr:root" + folderTargetPath + "//element(*," + WR_FOLDER_ENTITY_TYPE + ")[@name='"
                + folderName + "']";
        logger.info("Query string: {}", containerQuery);

        List<Entity> entityList = ws.getQueryManager().executeQuery(containerQuery).getResults();
        if (entityList.isEmpty()) {
            oFolder = ws.createNewEntity(WR_FOLDER_ENTITY_TYPE, folderTarget);
            oFolder.setProperty("name", folderName);
            oFolder.setProperty("description", folderName);
            oFolder.persist();
            ws.save();
        } else {
            oFolder = (Entity) entityList.get(0);
        }
        return oFolder;
    }
    
    private Connection getConnectionForArcaresDB() {
        // recupera opzioni di collegamento al DB di Arcares
        StringBuilder query = new StringBuilder().append("/jcr:root").append("//element(*,opzioneArcares)");
        QueryManager qm = ws.getQueryManager();
        List<Entity> list = qm.executeQuery(query.toString()).getResults();
        Entity optArcares = (Entity) list.get(0);

        // connessione al db
        Connection con = null;
        try {
            Class.forName((String) optArcares.getPropertyValue("jdbcDriver"));
            con = DriverManager.getConnection((String) optArcares.getPropertyValue("dataSource"),
                    (String) optArcares.getPropertyValue("nomeUtente"),
                    (String) optArcares.getPropertyValue("password"));
//		} catch (ModelException | SQLException | ClassNotFoundException e) {
        } catch (Exception e) {
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.debug(ExceptionUtils.getStackTrace(e));
            throw new RuntimeException("ERRORE IN RuleWFrecuperaDati: " + e.getMessage(), e);
        }
        return con;
    }
}
