package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.QueryManager;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.repository.entities.StructuredProperty;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import it.cbt.wr.api.service.repository.qualities.Resource;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.rolservice.ArrayOfDestinatario;
import it.cbt.wr.rolservice.CEResult;
import it.cbt.wr.rolservice.Destinatario;
import it.cbt.wr.rolservice.Documento;
import it.cbt.wr.rolservice.GetStatoIdRichiestaResult;
import it.cbt.wr.rolservice.Indirizzo;
import it.cbt.wr.rolservice.InvioResult;
import it.cbt.wr.rolservice.ROLServiceSoap;
import it.cbt.wr.rolservice.ROLSubmit;
import it.cbt.wr.rolservice.Mittente;
import it.cbt.wr.rolservice.Nominativo;
import it.cbt.wr.rolservice.PreConfermaResult;
import it.cbt.wr.rolservice.RecuperaIdRichiestaResult;
import it.cbt.wr.rolservice.Richiesta;
import it.cbt.wr.rolservice.ServizioEnquiryResponse;
import it.cbt.wr.rolservice.ValidaDestinatariResults;
import it.cbt.wr.rolservice.ValorizzaResult;
import it.cbt.wr.rolservice.client.ROLClient;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.datatype.DatatypeFactory;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONObject;

public class RuleWFpostaRegistrata implements WrRuleClassBody {
    WorkSession ws; // Required
    Logger logger;	// Required
    Entity entity;	// Required
    String destinatariJSON;

    void setParameters(Map parameters) {
        logger.info("RuleWFpostaRegistrata: setParameters...");
        ws = (WorkSession) parameters.get("workSession");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
        destinatariJSON = (String) parameters.get("destinatariJSON");            
    }

    public Object run(Map parameters) {
        logger = (Logger) parameters.get("logger");
        logger.info("RuleWFpostaRegistrata: Inizio");
        setParameters(parameters);
        
        boolean wasSkippingPolicies = ws.isSkippingPolicies();
 
        HashMap retMap = new HashMap();
        retMap.put("globalError", false);
        String errorMessage = null;
        
        List<File> filesToDelete = new ArrayList();
        try {
            ws.setSkipPolicies(Boolean.TRUE); 
            String optProt = "/jcr:root//element(*,opzioniPoste)";
            QueryManager qm = ws.getQueryManager();
            List<Entity> optionList = qm.executeQuery(optProt).getResults();
            Entity option;
            if(optionList.size()==1){
                 option = (Entity) optionList.get(0);
            }else{
                throw new Exception("Errore nel recupero dell'entità opzioniPoste");
            }		

            String url = (String) option.getPropertyValue("url");
            String user = (String) option.getPropertyValue("user");
            String password = (String) option.getPropertyValue("password");
            String jsonMittente = (String) option.getPropertyValue("jsonMittente");
            String cliente = (String) option.getPropertyValue("cliente");
            
            ROLClient rolClient = new ROLClient();
            
            ROLServiceSoap rolService = rolClient.bindToWebServices(url + "/ROLGC/RolService.svc", user, password);
            
            RecuperaIdRichiestaResult recuperaIdRichiestaResult = rolService.recuperaIdRichiesta();
            String idRichiesta = recuperaIdRichiestaResult.getIDRichiesta();
            CEResult cerRirr = recuperaIdRichiestaResult.getCEResult();
            logger.info("RuleWFpostaRegistrata - idRichiesta: "+idRichiesta);

            if (cerRirr.getType().equals("I")) {
                //
                // validaDestinatari
                //
                JSONObject destinatariObj = new JSONObject(destinatariJSON);
                // Oggetto JSON memorizzato nel DB (Template)
                // {"cognome": "ITALIANE","nome": "POSTE","dug": "VIALE","toponimo": "EUROPA","numerocivico": "175",
                //	"città": "ROMA","cap": "00144","provincia": "RM","stato": "Italia"}
                String cognomeDestinatario = destinatariObj.getString("Cognome");
                String nomeDestinatario = destinatariObj.getString("Nome");
                String dugDestinatario = destinatariObj.getJSONObject("Indirizzo").getString("DUG");
                String complementoIndirizzo = destinatariObj.getString("ComplementoIndirizzo");
                String complementoNominativo = destinatariObj.getString("ComplementoNominativo");
                
                String toponimoDestinatario = destinatariObj.getJSONObject("Indirizzo").getString("toponimo");
                String numerocivicoDestinatario = destinatariObj.getJSONObject("Indirizzo").getString("numeroCivico");
                String esponente = destinatariObj.getJSONObject("Indirizzo").getString("esponente");
                
                String cittaDestinatario = destinatariObj.getString("Citta");
                String capDestinatario = destinatariObj.getString("CAP");
                String provinciaDestinatario = destinatariObj.getString("Provincia");
                String statoDestinatario = destinatariObj.getString("Stato");
                String casellaPostale = destinatariObj.getString("CasellaPostale");
                String frazione = destinatariObj.getString("Frazione");
                String ragioneSociale = destinatariObj.getString("RagioneSociale");
                String telefono = destinatariObj.getString("Telefono");
                String tipoIndirizzo = destinatariObj.getString("TipoIndirizzo");
                String ufficioPostale = destinatariObj.getString("UfficioPostale");
                String zona = destinatariObj.getString("Zona");
                logger.info("RuleWFpostaRegistrata - statoDestinatario: {}", statoDestinatario);
                Destinatario destinatario = new Destinatario();
                // Nominativo
                Nominativo nominativo = new Nominativo();
                // Nominativo:indirizzo
                
                Indirizzo indirizzo = new Indirizzo();
                indirizzo.setDUG(dugDestinatario);
                indirizzo.setToponimo(toponimoDestinatario); 
                indirizzo.setNumeroCivico(numerocivicoDestinatario);
                indirizzo.setEsponente(esponente);
                
                nominativo.setIndirizzo(indirizzo);
                nominativo.setZona(zona);
                nominativo.setTipoIndirizzo(tipoIndirizzo);
                nominativo.setStato(statoDestinatario);
                nominativo.setTelefono(telefono);
                nominativo.setForzaDestinazione(false);
                nominativo.setCasellaPostale(casellaPostale);
                nominativo.setUfficioPostale(ufficioPostale);
                nominativo.setProvincia(provinciaDestinatario);
                nominativo.setComplementoNominativo("DEST CN");
                nominativo.setRagioneSociale(ragioneSociale);
                nominativo.setCognome(cognomeDestinatario);
                nominativo.setNome(nomeDestinatario);
                nominativo.setFrazione(frazione);
                nominativo.setCitta(cittaDestinatario);
                nominativo.setComplementoIndirizzo("DEST CI");                
                nominativo.setCAP(capDestinatario);
                
                destinatario.setNominativo(nominativo);
                destinatario.setIdDestinatario("1");
                //destinatario.setIdRicevuta("ID_RICEVUTA");
                ArrayOfDestinatario destinatari = new ArrayOfDestinatario();
                destinatari.getDestinatario().add(destinatario);

                ValidaDestinatariResults validaDestinatariResult = rolService.validaDestinatari(idRichiesta, destinatari);

                logger.info("--validaDestinatari--");
                CEResult cerVdr = validaDestinatariResult.getCEResult();
                logger.info(cerVdr.getType());
                logger.info(cerVdr.getCode());
                logger.info(cerVdr.getDescription());

                if (cerVdr.getType().equals("I") || cerVdr.getType().equals("W")) {
                    if(cerVdr.getType().equals("W")){
                        errorMessage = cerVdr.getDescription();
                    }
                    //
                    // invio
                    //
                    // LOLSubmit
                    ROLSubmit rolSubmit = new ROLSubmit();
                    rolSubmit.setForzaInvioDestinazioniValide(true); // REQUIRED
                    // Mittente
                    JSONObject mittentiObj = new JSONObject(jsonMittente);
                    // Oggetto JSON memorizzato nel DB (Template)
                    // {"cognome": "ITALIANE","nome": "POSTE","dug": "VIALE","toponimo": "EUROPA","numerocivico": "175",
                    //	"città": "ROMA","cap": "00144","provincia": "RM","stato": "Italia"}
                    String telefonoMittente = mittentiObj.getString("telefono");
                    String ragioneSocialeMittente = mittentiObj.getString("ragioneSociale");
                    String dugMittente = mittentiObj.getString("dug");
                    String toponimoMittente = mittentiObj.getString("toponimo");
                    String numerocivicoMittente = mittentiObj.getString("numerocivico");
                    String cittaMittente = mittentiObj.getString("città");
                    String capMittente = mittentiObj.getString("cap");
                    String provinciaMittente = mittentiObj.getString("provincia");
                    String statoMittente = mittentiObj.getString("stato");
                    
                    Mittente mittente = new Mittente();
                    mittente.setInviaStampa(false); // REQUIRED
                    // Mittente:Nominativo
                    Nominativo nominativoMittente = new Nominativo();
                    //nominativoMittente.setZona("ZONA");
                    nominativoMittente.setTipoIndirizzo("NORMALE");
                    nominativoMittente.setStato(statoMittente);
                    nominativoMittente.setTelefono(telefonoMittente);
                    nominativoMittente.setForzaDestinazione(false); // REQUIRED
                    nominativoMittente.setCasellaPostale("");
                    nominativoMittente.setProvincia(provinciaMittente);
                    nominativoMittente.setComplementoNominativo("MITT CN");
                    nominativoMittente.setRagioneSociale(ragioneSocialeMittente);
                    nominativoMittente.setCognome("");
                    nominativoMittente.setNome("");
                    nominativoMittente.setCitta(cittaMittente);
                    nominativoMittente.setComplementoIndirizzo("MITT CI");
                    nominativoMittente.setCAP(capMittente);
                    // Mittente:Nominativo:Indirizzo
                    Indirizzo indirizzoMittente = new Indirizzo();
                    indirizzoMittente.setDUG(dugMittente);
                    indirizzoMittente.setToponimo(toponimoMittente); 
                    indirizzoMittente.setNumeroCivico(numerocivicoMittente);
                    indirizzoMittente.setEsponente("");
                    nominativoMittente.setIndirizzo(indirizzoMittente);
                    mittente.setNominativo(nominativoMittente);
                    rolSubmit.setMittente(mittente);
                    // Destinatari (precedentemente validati)
                    rolSubmit.setDestinatari(destinatari);
                    // Numero destinatari (precedentemente validati)
                    rolSubmit.setNumeroDestinatari(destinatari.getDestinatario().size());
                    
                    StructuredProperty resources = entity.getStructuredProperty(Resource.RESOURCES_PROPERTY);
                    List strRecords = resources.getRecords();
                    if (strRecords != null && strRecords.size() > 0) {
                        for (int j = 0; j < strRecords.size(); j++) {
                            Documento documento = new Documento();
                    
                            StructuredPropertyRecord curRecord = (StructuredPropertyRecord) strRecords.get(j);
                            String fileExtension = "" + curRecord.getPropertyValue("fileExtension");
                            
                            String originalFilename = "" + curRecord.getPropertyValue("originalFilename");
                            logger.info("RuleWFpostaRegistrata - originalFilename {}", originalFilename);
                            documento.setTipoDocumento(fileExtension);
                            
                            InputStream is = (InputStream) curRecord.getPropertyValue("data");
                            
                            File templateFile = File.createTempFile("tmp", fileExtension);
                            filesToDelete.add(templateFile);
                            OutputStream os = new FileOutputStream(templateFile);
                            IOUtils.copy(is, os);
                            IOUtils.closeQuietly(is);
                            IOUtils.closeQuietly(os);
                            
                            // Documento:immagine
                            Path path = Paths.get(templateFile.toURI());
                            
                            // Documento:immagine
                            byte[] immagine;
                            try {
                                immagine = Files.readAllBytes(path);
                                documento.setImmagine(immagine);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            // Documento:MD5
                            try {
                                
                                FileInputStream fis = new FileInputStream(templateFile);
                                String md5 = org.apache.commons.codec.digest.DigestUtils.md5Hex(fis);
                                logger.info("RuleWFpostaRegistrata - md5 {}", md5);
                                fis.close();
                                documento.setMD5(md5);
                            } catch (IOException e1) {
                                System.out.println(e1.getMessage());
                            }
                    
                            rolSubmit.getDocumento().add(documento);
                        }
                    }
                    // LOLSubmitOpzioni
                    ROLSubmit.Opzioni rOLSubmitOpzioni = new ROLSubmit.Opzioni();
                    rOLSubmitOpzioni.setSecurPaper(false);
                    rOLSubmitOpzioni.setInserisciMittente(false);
                    // Crea un XMLGregorianCalendar con la data odierna
                    GregorianCalendar calendarLOL = new GregorianCalendar();
                    DatatypeFactory datatypeFactoryLOL = DatatypeFactory.newInstance();
                    rOLSubmitOpzioni.setDataStampa(datatypeFactoryLOL.newXMLGregorianCalendar(calendarLOL));
                    //
                    rOLSubmitOpzioni.setFirmaElettronica(false);
                    rOLSubmitOpzioni.setArchiviazione(false);
                    rOLSubmitOpzioni.setDPM(false);
                    // LOLSubmitOpzioniInserti
                    ROLSubmit.Opzioni.Inserti rOLSubmitOpzioniInserti = new ROLSubmit.Opzioni.Inserti();
                    rOLSubmitOpzioniInserti.setInserisciMittente(false);  // REQUIRED
                    //lOLSubmitOpzioniInserti.setInserto("");  // REQUIRED
                    rOLSubmitOpzioni.setInserti(rOLSubmitOpzioniInserti);
                    rolSubmit.setOpzioni(rOLSubmitOpzioni);

                    InvioResult invioResult = rolService.invio(idRichiesta, cliente, rolSubmit);

                    logger.info("--invio--");
                    logger.info(invioResult.getCEResult().getType());
                    logger.info(invioResult.getCEResult().getCode());
                    logger.info(invioResult.getCEResult().getDescription());

                    if (invioResult.getCEResult().getType().equals("I")) {
                        // I = "Presa in Carico"
                        //
                        // valorizza
                        //
                        String guidUtente = invioResult.getGuidUtente();
                        logger.info("RuleWFpostaRegistrata - guidUtente {}", guidUtente);
                        Richiesta richiesta = new Richiesta();
                        richiesta.setIDRichiesta(idRichiesta);
                        logger.info("RuleWFpostaRegistrata - idRichiesta {}", idRichiesta);
                        richiesta.setGuidUtente(guidUtente);
                        List<Richiesta> richieste = 
                                new ArrayList<Richiesta>();
                        richieste.add(richiesta);

                        String codeValorizza = null;
                        ValorizzaResult valorizzaResult=null;
                        do {
                            // Temporizzazione da specifiche pag.34 del documento
                            // Postaonline H2H All 9 - Web Service Specifiche Interfaccia OLTP - v5.4.pdf
                            try {
                                Thread.sleep(60000);
                            } catch (InterruptedException e) {
                                System.out.println(e.getMessage());
                            }

                            valorizzaResult = rolService.valorizza(richieste);
                            logger.info("--valorizza--");
                            // E - Errore di sistema
                            // I - Tutte le richieste con prezzo
                            // W - Alcune richieste non disponibili
                            String typeGlobalValorizza = valorizzaResult.getCEResult().getType();
                            logger.info("global");
                            logger.info("type {} ", typeGlobalValorizza);
                            logger.info("description {} " , valorizzaResult.getCEResult().getDescription());

                            // ServizioEnquiryResponse
                            // E - 		Errore di sistema
                            // W - E Alcune richieste senza prezzo
                            // W - W Alcune richieste non disponibili
                            // I - I Tutte le richieste con prezzo
                            List<ServizioEnquiryResponse> servizioEnquiryResponses = valorizzaResult.getServizioEnquiryResponse();
                            for (int ser=0; ser<servizioEnquiryResponses.size();ser++) {
                                ServizioEnquiryResponse serObj = (ServizioEnquiryResponse) servizioEnquiryResponses.get(ser);
                                CEResult crSer = (CEResult) serObj.getCEResult();
                                String typeLocalValorizza = (String) crSer.getType();
                                logger.info("local "+ser);
                                logger.info("type {} ", typeGlobalValorizza);
                                logger.info("description {}", valorizzaResult.getCEResult().getDescription());
                            }			

                            codeValorizza = valorizzaResult.getCEResult().getType();
                            logger.info("code {} " , valorizzaResult.getCEResult().getCode());
                            logger.info("type {}" , codeValorizza);
                            logger.info("description {} ", valorizzaResult.getCEResult().getDescription());

                        } while (codeValorizza.equals("R") ||
                                    codeValorizza.equals("N") || codeValorizza.equals("J") || 
                                    codeValorizza.equals("G") || codeValorizza.equals("Y"));

                        if (codeValorizza.equals("0000")) {
                            //
                            // preConferma
                            //

                            // i clienti con postfattuturazione possono utilizzare il metodo preconferma impostando 
                            // il parametro autoconfirm a true senza invocare successivamente la conferma
                            boolean autoconfirm = true;
                            PreConfermaResult preConfermaResult = rolService.preConferma(richieste, autoconfirm );

                            logger.info("--preConferma--");
                            logger.info("code {} ", preConfermaResult.getCEResult().getCode());
                            logger.info("type {} ", preConfermaResult.getCEResult().getType());
                            logger.info("description ", preConfermaResult.getCEResult().getDescription());

                            if (preConfermaResult.getCEResult().getCode().equals("0000")) {
                                //
                                // recuperaStatoIdRichiesta
                                //

                                // Vedi pag. 40 del documento 
                                // Postaonline H2H All 9 - Web Service Specifiche Interfaccia OLTP - v5.4.pdf

                                // Presa in Carico	(Transitorio)
                                // Valido (Transitorio)

                                // Presa in carico da DCS (CEResult.Type=”W” - Transitorio)
                                // Convertito (CEResult.Type=”W” - Transitorio)
                                // Preso in carico R&D (CEResult.Type=”W” - Transitorio)

                                // Non Convertito (CEResult.Type=”E” - Finale)
                                // Non valido (CEResult.Type=”E” - Finale)
                                // Pagine in eccesso (CEResult.Type=”E” - Finale)
                                // Non Prezzato (CEResult.Type=”E” - Finale)
                                // Annullato (CEResult.Type=”E” - Finale)
                                // Timeout Conferma (CEResult.Type=”E” - Finale)
                                // Timeout Postel (CEResult.Type=”E” - Finale)
                                // Errore negli Esiti (CEResult.Type=”E” - Finale)
                                // In Postalizzazione: Il servizio di postalizzazione ha iniziato ad inviare gli esiti, 
                                // 		ma non sono ancora arrivati per tutti i destinatari 
                                //		(CEResult.Type=”E” - Transitorio)				

                                // Prezzato (CEResult.Type=”I” - Finale)
                                // In Attesa di Delivery (CEResult.Type=”I” - Transitorio)
                                // Confermato (CEResult.Type=”I” - Transitorio)
                                // Presa in Carico Postel (CEResult.Type=”I” - Transitorio)
                                // 		il suo stato cambia dopo il ritorno dell’esito di stampa da considerarsi uno stato finale
                                // Postalizzato (Determina un CEResult.Type=”I” - Finale)

                                String codeRecuperaStato = null;
                                GetStatoIdRichiestaResult getStatoIdRichiestaResult = null;
                                do {
                                    getStatoIdRichiestaResult = rolService.recuperaStatoIdRichiesta(idRichiesta);

                                    logger.info("--recuperaStatoIdRichiesta--");
                                    codeRecuperaStato = getStatoIdRichiestaResult.getCeResult().getType();
                                    logger.info("code: {}", getStatoIdRichiestaResult.getCeResult().getCode());
                                    logger.info("type: {}", codeRecuperaStato);
                                    logger.info("description: {}", getStatoIdRichiestaResult.getCeResult().getDescription());

                                } while (codeRecuperaStato.equals("I") && 
                                            codeRecuperaStato.equals("W") &&
                                            codeRecuperaStato.equals("E") );

                                if (codeRecuperaStato.equals("I")) {
                                    entity.setProperty("pecUniqueId", idRichiesta);
                                    entity.setProperty("messageId", guidUtente);       
                                    logger.info("RuleWFpostaRegistrata - id aggiornati pecUniqueId {} guidUtente {}", idRichiesta, guidUtente);
                                } else if (codeRecuperaStato.equals("W")) {
                                    entity.setProperty("pecUniqueId", idRichiesta);
                                    entity.setProperty("messageId", guidUtente);  
                                    logger.info("RuleWFpostaRegistrata - id aggiornati pecUniqueId {} guidUtente {}", idRichiesta, guidUtente);
                                    errorMessage = invioResult.getCEResult().getDescription();
                                } else if (codeRecuperaStato.equals("E")) {
                                    retMap.put("globalError", true);
                                    errorMessage = "Errore in recupera stato richiesta";
                                }
                            }
                        } else if (codeValorizza.equals("J") || codeValorizza.equals("G") || codeValorizza.equals("Y")) {
                            // ERRORE
                            retMap.put("globalError", true);
                            errorMessage = "Errore di sistema/Alcuni Indirizzi non validi /Firma non Valida / Bollettini non Validi";
                        } else {
                            // Errore nel metodo invio
                            // “E” – “Errore di sistema”
                            // "W" - "Alcuni Indirizzi non validi /Firma non Valida / Bollettini non Validi"
                            retMap.put("globalError", true);
                            errorMessage = "Errore di sistema/Alcuni Indirizzi non validi /Firma non Valida / Bollettini non Validi";
                        }
                    } else if (invioResult.getCEResult().getType().equals("E")) {
                        retMap.put("globalError", true);
                        errorMessage = invioResult.getCEResult().getDescription();
                    }
                } else {
                    retMap.put("globalError", true);
                    errorMessage = cerVdr.getDescription();
                }
                
            }
            entity.setProperty("raccomandata", true);
            entity.setProperty("errorMessage", errorMessage);
            entity.persist();
            
            ws.save();
        } catch (Exception ex) {
            logger.warn("RuleWFpostaRegistrata Error: Executing action rule " + getClass().getName(), ex);
            logger.error("RuleWFpostaRegistrata Error: {}", ExceptionUtils.getMessage(ex));
            logger.debug(ExceptionUtils.getStackTrace(ex));

            errorMessage = ex.getMessage();
            errorMessage = errorMessage.substring(errorMessage.indexOf(":") + 1);
           
            logger.error(errorMessage + " per mail: " + entity.getId());
            entity.setProperty("errorMessage", errorMessage);
            entity.persist();
            
            ws.save();
            retMap.put("globalError", true);

        } finally {
            ws.setSkipPolicies(wasSkippingPolicies);
            logger.info("RuleWFpostaRegistrata: End");
            
            for (File source : filesToDelete){
                source.delete();
            }    

            retMap.put("errorMessage", errorMessage);
        }
        return retMap;
    }
}

