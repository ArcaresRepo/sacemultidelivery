package rules.arcares;

import java.io.InputStream;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONObject;
import org.slf4j.Logger;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.QueryManager;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.ContainedEntities;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.repository.entities.StructuredProperty;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import it.cbt.wr.api.service.repository.qualities.Resource;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.utils.ISO9075;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.json.JSONArray;

public class RuleWFinviaMail implements WrRuleClassBody {

    WorkSession ws; // Required
    Logger logger;	// Required
    Entity entity;	// Required

    Connection con = null;
    private Statement updateStatemant;
    private final String updateDBArcaresRuleName = "UpdateDBArcares";

    private final String statoOk = "2";

    boolean flagConserva = false;

    String errorPoste = "";
    String errorMail = "";

    void setParameters(Map parameters) {
        logger.info("RuleWFinviaMail: setParameters...");
        ws = (WorkSession) parameters.get("workSession");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    public Object run(Map parameters) {
        logger = (Logger) parameters.get("logger");
        logger.info("RuleWFinviaMail: Inizio");
        setParameters(parameters);
        boolean wasSkippingPolicies = ws.isSkippingPolicies();
        HashMap retMap = new HashMap();
        String idFlusso = null;
        String idDocumento = null;
        try {
            ws.setSkipPolicies(Boolean.TRUE);
            con = getConnectionForArcaresDB();

            idFlusso = (String) entity.getPropertyValue("idFlusso");
            idDocumento = ((String) entity.getPropertyValue("idDocumento")).trim();

            String statoInvio = (String) entity.getPropertyValue("statoInvio");
            String statoInvioSec = (String) entity.getPropertyValue("statoInvioSec");

            flagConserva = (Boolean) entity.getPropertyValue("flagConserva");

            String tipoDocumento = entity.getEntityType();

            ContainedEntities outgoingMailContained = entity.getContainedEntities("outgoingMail");
            if (outgoingMailContained != null && !outgoingMailContained.isEmpty()) {
                String select = "SELECT * FROM WRDC_DETAIL AS DET "
                        + "JOIN WRDC_INDIR AS IND "
                        + "ON DET.ID_FLUSSO=IND.ID_FLUSSO AND DET.ID_DESTINAZIONE=IND.ID_DESTINAZIONE "
                        + "WHERE (IND.ID_CANALE_PRIM='EMAIL' OR IND.ID_CANALE_PRIM='PEC' "
                        + "OR IND.ID_CANALE_SEC='EMAIL' OR IND.ID_CANALE_SEC='PEC' )"
                        + "AND DET.ID_FLUSSO='" + idFlusso + "' AND DET.ID_DOCUMENTO='" + idDocumento + "'";
                logger.info("RuleWFinviaMail: Recupera Dati Flusso {} Documento {}", idFlusso, idDocumento);

                updateStatemant = con.createStatement();
                ResultSet res = updateStatemant.executeQuery(select);
                while (res.next()) {
                    // Recupera dal Db oggetto JSON 
                    String subjectBodyMessageJSON = res.getString("DES_TESTO_MAIL");
                    JSONObject subjectBodyMessageObj = new JSONObject(subjectBodyMessageJSON);
                    String subjectMessage = subjectBodyMessageObj.getString("oggetto");
                    String bodyMessage = subjectBodyMessageObj.getString("corpo");
                    String idDestinazione = res.getString("ID_CANALE_PRIM");
                    String idDestinazioneSec = res.getString("ID_CANALE_SEC");

                    if (idDestinazione != null) {
                        idDestinazione = idDestinazione.trim();
                        if (statoInvio == null || !statoInvio.equals(statoOk)) {
                            if (idDestinazione.equalsIgnoreCase("EMAIL") || idDestinazione.equalsIgnoreCase("PEC")) {
                                for (Object ent : outgoingMailContained) {
                                    Entity outgoingMail = (Entity) ent;
                                    boolean principale = (Boolean) outgoingMail.getPropertyValue("principale");
                                    logger.info("RuleWFinviaMail: principale {}", principale);
                                    if (principale) {
                                        String destinatari = "";
                                        if (idDestinazione.equalsIgnoreCase("EMAIL")) {
                                            destinatari = res.getString("DES_IND_MAIL");
                                        } else {
                                            destinatari = res.getString("DES_IND_PEC");
                                        }
                                        boolean sendEmail = sendEmailOrPec(outgoingMail, destinatari, subjectMessage, bodyMessage, true);
                                        aggiornaDocumento(sendEmail, idFlusso, idDocumento, principale, errorMail);
                                    }
                                }
                            }
                        }
                        idDestinazioneSec = idDestinazioneSec.trim();
                        if (statoInvioSec == null || !statoInvioSec.equals(statoOk)) {
                            if (idDestinazioneSec.equalsIgnoreCase("EMAIL") || idDestinazioneSec.equalsIgnoreCase("PEC")) {
                                for (Object ent : outgoingMailContained) {
                                    Entity outgoingMail = (Entity) ent;
                                    boolean principale = (Boolean) outgoingMail.getPropertyValue("principale");
                                    logger.info("RuleWFinviaMail: principale {}", principale);
                                    if (!principale) {
                                        String destinatari = "";
                                        if (idDestinazioneSec.equalsIgnoreCase("EMAIL")) {
                                            destinatari = res.getString("DES_IND_MAIL");
                                        } else {
                                            destinatari = res.getString("DES_IND_PEC");
                                        }
                                        boolean sendEmail = sendEmailOrPec(outgoingMail, destinatari, subjectMessage, bodyMessage, true);

                                        logger.info("RuleWFinviaMail: Invio PEC A {} Oggetto {} Messaggio {}", destinatari, subjectMessage, bodyMessage);
                                        aggiornaDocumento(sendEmail, idFlusso, idDocumento, principale, errorMail);
                                    }
                                }
                            }
                        }
                    }
                }
                res.close();
                if (updateStatemant != null) {
                    try {
                        updateStatemant.close();
                    } catch (SQLException e) {
                        logger.error("error: {}", ExceptionUtils.getMessage(e));
                        logger.debug(ExceptionUtils.getStackTrace(e));
                    }
                }
            }

            ContainedEntities postalDocsContained = entity.getContainedEntities("documentoPostale");
            if (postalDocsContained != null && !postalDocsContained.isEmpty()) {
                logger.info("RuleWFinviaMail: Numero di postalDocs {}", postalDocsContained.size());

                String select = "SELECT * FROM WRDC_DETAIL AS DET "
                        + "JOIN WRDC_INDIR AS IND "
                        + "ON DET.ID_FLUSSO=IND.ID_FLUSSO AND DET.ID_DESTINAZIONE=IND.ID_DESTINAZIONE "
                        + "WHERE (IND.ID_CANALE_PRIM='ORDINARY_MAIL' OR IND.ID_CANALE_PRIM='REGISTERED_MAIL' "
                        + "OR IND.ID_CANALE_SEC='ORDINARY_MAIL' OR IND.ID_CANALE_SEC='REGISTERED_MAIL' )"
                        + "AND DET.ID_FLUSSO='" + idFlusso + "' AND DET.ID_DOCUMENTO='" + idDocumento + "'";
                logger.info("RuleWFinviaMail: Recupera Dati Flusso {} Documento {}", idFlusso, idDocumento);
                logger.info("RuleWFinviaMail: select {}", select);

                updateStatemant = con.createStatement();
                ResultSet res = updateStatemant.executeQuery(select);
                while (res.next()) {
                    // Recupera dal Db oggetto JSON     
                    String destinatariJSON = res.getString("DES_IND_POST");
                    String idDestinazione = res.getString("ID_CANALE_PRIM");
                    String idDestinazioneSec = res.getString("ID_CANALE_SEC");

                    idDestinazione = idDestinazione.trim();
                    if (statoInvio == null || !statoInvio.equals(statoOk)) {
                        if (idDestinazione.equalsIgnoreCase("ORDINARY_MAIL")) {
                            for (Object ent : postalDocsContained) {
                                Entity postalDoc = (Entity) ent;
                                boolean principale = (Boolean) postalDoc.getPropertyValue("principale");
                                logger.info("RuleWFinviaMail: principale {}", principale);
                                if (principale) {
                                    boolean sendPoste = sendPosteMail(idDocumento, tipoDocumento, postalDoc,
                                            destinatariJSON, true);
                                    aggiornaDocumento(sendPoste, idFlusso, idDocumento, principale, errorPoste);
                                    break;
                                }
                            }
                        } else if (idDestinazione.equalsIgnoreCase("REGISTERED_MAIL")) {
                            for (Object ent : postalDocsContained) {
                                Entity postalDoc = (Entity) ent;
                                boolean principale = (Boolean) postalDoc.getPropertyValue("principale");
                                logger.info("RuleWFinviaMail: principale {}", principale);
                                if (principale) {
                                    boolean sendPoste = sendPosteMail(idDocumento, tipoDocumento, postalDoc,
                                            destinatariJSON, false);
                                    aggiornaDocumento(sendPoste, idFlusso, idDocumento, principale, errorPoste);
                                    break;
                                }
                            }
                        }
                    }
                    idDestinazioneSec = idDestinazioneSec.trim();
                    if (statoInvioSec == null || !statoInvioSec.equals(statoOk)) {
                        if (idDestinazioneSec.equalsIgnoreCase("ORDINARY_MAIL")) {
                            for (Object ent : postalDocsContained) {
                                Entity postalDoc = (Entity) ent;
                                boolean principale = (Boolean) postalDoc.getPropertyValue("principale");
                                if (!principale) {
                                    boolean sendPoste = sendPosteMail(idDocumento, tipoDocumento, postalDoc,
                                            destinatariJSON, true);
                                    aggiornaDocumento(sendPoste, idFlusso, idDocumento, principale, errorPoste);
                                    break;
                                }
                            }
                        } else if (idDestinazioneSec.equalsIgnoreCase("REGISTERED_MAIL")) {
                            for (Object ent : postalDocsContained) {
                                Entity postalDoc = (Entity) ent;
                                boolean principale = (Boolean) postalDoc.getPropertyValue("principale");
                                logger.info("RuleWFinviaMail: Document ID  {} sending ok!", idDocumento);
                                if (!principale) {
                                    boolean sendPoste = sendPosteMail(idDocumento, tipoDocumento, postalDoc,
                                            destinatariJSON, false);
                                    aggiornaDocumento(sendPoste, idFlusso, idDocumento, principale, errorPoste);
                                    break;
                                }
                            }
                        }
                    }
                }
                res.close();
                if (updateStatemant != null) {
                    try {
                        updateStatemant.close();
                    } catch (SQLException e) {
                        logger.error("error: {}", ExceptionUtils.getMessage(e));
                        logger.debug(ExceptionUtils.getStackTrace(e));
                    }
                }
            }

            logger.info("RuleWFinviaMail: Document ID  {} sending ok!", idDocumento);

            retMap.put("conserva", flagConserva);

        } catch (Exception ex) {

            String updateError = "UPDATE WRDC_DETAIL "
                    + "SET COD_STATO = '4', COD_STATO_DLVRY = '3', DES_ERRORE_DLVRY = 'Errore Tecnico Invio'  "
                    + "WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
            try {
                HashMap paramsUpdate = new HashMap();
                paramsUpdate.put("workSession", ws);
                paramsUpdate.put("logger", logger);
                paramsUpdate.put("query", updateError);
                logger.info("calling externalRule " + updateDBArcaresRuleName);
                WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
            } catch (Exception e) {
                logger.error("error: {}", ExceptionUtils.getMessage(e));
                logger.debug(ExceptionUtils.getStackTrace(e));
                throw new RuntimeException("ERRORE in RuleWFfirma (ATTENZIONE STATO DELLA FIRMA NON AGGIORNATO IN ERRORE NEL DB ARCARES!!!!): " + e.getMessage(), e);
            }
            Entity entityToUpdate = ws.getEntityById(entity.getId());
            entityToUpdate.setProperty("statoInvio", "3");
            entityToUpdate.setProperty("statoInvioSec", "3");
            entityToUpdate.setProperty("stato", "4");

            entityToUpdate.persist();

            try {
                ws.save();
            } catch (Exception e) {
                logger.error("error: {}", ExceptionUtils.getMessage(e));
                logger.debug(ExceptionUtils.getStackTrace(e));
                throw new RuntimeException("ERRORE in RuleWFinviaMail (ATTENZIONE STATO INVIO NON AGGIORNATO SU WR!!!!): " + e.getMessage(), e);
            }

            logger.warn("RuleWFinviaMail Error: Executing action rule " + getClass().getName(), ex);
            logger.error("RuleWFinviaMail Error: {}", ExceptionUtils.getMessage(ex));
            logger.debug(ExceptionUtils.getStackTrace(ex));
            throw new RuntimeException("RuleWFinviaMail Error: Oops, something goes wrong: " + ex.getMessage(), ex);
        } finally {
            ws.setSkipPolicies(wasSkippingPolicies);
            logger.info("RuleWFinviaMail: End");

            if (con != null) {
                try {
                    con.close();
                } catch (SQLException e) {
                    logger.error("RuleWFinviaMail Database Connection Error: {}", ExceptionUtils.getMessage(e));
                }
            }
        }
        return retMap;
    }

    private void costruisciAllegato(Entity mail) {
        int numDoc = 0;
        logger.info("RuleWFinviaMail: CostruzioniAllegato - Inizio");
        StructuredProperty newResources = mail.getStructuredProperty(Resource.RESOURCES_PROPERTY);
        StructuredProperty resources = entity.getStructuredProperty(Resource.RESOURCES_PROPERTY);
        
        newResources.removeAllRecords();
        
        List strRecords = resources.getRecords();
        if (strRecords != null && strRecords.size() > 0) {
            for (int j = 0; j < strRecords.size(); j++) {
                StructuredPropertyRecord curRecord = (StructuredPropertyRecord) strRecords.get(j);
                String fileExtension = "" + curRecord.getPropertyValue("fileExtension");
                Long fileSize = (Long) curRecord.getPropertyValue("fileSize");
                String description = "" + curRecord.getPropertyValue("description");
                String encoding = "" + curRecord.getPropertyValue("encoding");
                String originalFilename = "" + curRecord.getPropertyValue("originalFilename");
                String mimeType = "" + curRecord.getPropertyValue("mimeType");
                InputStream data = (InputStream) curRecord.getPropertyValue("data");

                StructuredPropertyRecord newRecord = newResources.createRecord();
                newRecord.setProperty("fileExtension", fileExtension);
                newRecord.setProperty("fileSize", fileSize);
                newRecord.setProperty("description", description);
                newRecord.setProperty("encoding", encoding);
                newRecord.setProperty("originalFilename", originalFilename);
                newRecord.setProperty("mimeType", mimeType);
                newRecord.setProperty("data", data);
                newResources.addRecord(newRecord);
            }
        }
        logger.info("RuleWFinviaMail: CostruzioniAllegato - Fine");
    }

    private List executeQuery(String xQuery, Entity searchInfolder) {
        StringBuilder queryXpath = new StringBuilder();
        if (searchInfolder != null) {
            queryXpath.append("/jcr:root");
            queryXpath.append(ISO9075.encodePath(searchInfolder.getPath()));
        }
        queryXpath.append(xQuery);
        logger.debug("Query xpath: " + queryXpath.toString());
        List<Entity> queryResult = ws.getQueryManager().executeQuery(queryXpath.toString()).getResults();
        logger.info("Elementi trovati: " + queryResult.size());
        return queryResult;
    }

    // ordinaryRegistered = false -> ordinary; ordinaryRegistered = true -> registered
    private boolean sendPosteMail(String idDocOnDB, String tipoDocumento, Entity documentoPostale,
            String destinatariJSON, Boolean ordinaryRegistered) throws Exception {
        // Invio al WS di spedizione postale

        boolean sendPoste = false;
        costruisciAllegato(documentoPostale);
        documentoPostale.persist();
        ws.save();
        String postaRuleName = null;

        if (ordinaryRegistered) {
            // Invio posta ordinaria
            postaRuleName = "RuleWFpostaOrdinaria";
        } else {
            // Invio posta registrata
            postaRuleName = "RuleWFpostaRegistrata";
        }

        HashMap paramsPosteMail = new HashMap();
        paramsPosteMail.put("workSession", ws);

        List entities = new ArrayList();
        entities.add(documentoPostale);
        paramsPosteMail.put("entities", entities);//Action.ENTITIES_PARAMETER
        paramsPosteMail.put("logger", logger);
        paramsPosteMail.put("destinatariJSON", destinatariJSON);

        logger.info("calling externalRule " + postaRuleName);
        HashMap posteRes = (HashMap) WrApiUtils.evaluateRule(ws, postaRuleName, paramsPosteMail);
        Object tmpError = posteRes.get("globalError");
        errorPoste = null;
        errorPoste = (String) posteRes.get("errorMessage");
        if (errorPoste != null && errorPoste.equals("null")) {
            errorPoste = null;
        }
        boolean error = true;
        if (tmpError != null) {
            error = ((Boolean) tmpError).booleanValue();
        }
        if (!error) {
            sendPoste = true;
            // Aggiornamento tabella WRDC_RICEVUTE
            // inserite per ogni ID_DOCUMENTO ...
            // ... Id e il tipo dell'entity documentoPostale 
            StructuredProperty wfStr = documentoPostale.getStructuredProperty("workflow");
            String instanceId = null, taskId = null;

            List strRecords = wfStr.getRecords();

            StructuredPropertyRecord curRecord = (StructuredPropertyRecord) strRecords.get(0);
            instanceId = "" + curRecord.getProperty("instanceId").getValue();
            taskId = "" + curRecord.getProperty("taskId").getValue();

            documentoPostale.signal(instanceId, taskId);
            ws.save();

            String entityId = documentoPostale.getId();
            String entityType = documentoPostale.getEntityType();
            String insert = "INSERT INTO WRDC_RICEVUTE (ID_DOCUMENTO, ID_WR_CHIAVE, COD_CLASSE_DOC, COD_ENTITY_DOC) "
                    + "VALUES ('" + idDocOnDB + "', '" + entityId + "', '" + entityType + "', '" + tipoDocumento + "')";

            HashMap paramsInsert = new HashMap();
            paramsInsert.put("workSession", ws);
            paramsInsert.put("logger", logger);
            paramsInsert.put("query", insert);
            logger.info("calling externalRule " + updateDBArcaresRuleName);
            WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsInsert);

        }
        return sendPoste;
    }

    // mailPec = false -> mail; mailPec = true -> pec
    private boolean sendEmailOrPec(Entity outgoingMail, String destinatariJSON,
            String subjectMessage, String bodyMessage,
            boolean mailPec) {
        // imposta mail
        boolean sendEmail = false;
        try {

            logger.info("destinatariJSON: " + destinatariJSON);
            JSONObject destinatariObj = new JSONObject(destinatariJSON);
            // to
            ArrayList<String> toList = new ArrayList<String>();
            JSONArray to = destinatariObj.getJSONArray("to");
            logger.info("toJSON: " + to);
            int i = 0;
            while (i < to.length()) {
                String sTO = to.getString(i);
                if (sTO != null && !sTO.isEmpty()) {
                    toList.add(sTO);
                }
                i++;
            }
            if (!toList.isEmpty()) {
                outgoingMail.setProperty("to", toList);
            }
            // cc
            ArrayList<String> ccList = new ArrayList<String>();
            JSONObject destinatariCopiaObj = new JSONObject(destinatariJSON);
            JSONArray cc = destinatariCopiaObj.getJSONArray("cc");
            logger.info("ccJSON: " + cc);
            i = 0;
            while (i < cc.length()) {
                String sCC = cc.getString(i);
                if (sCC != null && !sCC.isEmpty()) {
                    ccList.add(sCC);
                }
                i++;
            }
            if (!ccList.isEmpty()) {
                outgoingMail.setProperty("cc", ccList);
            }
            outgoingMail.setProperty("subject", subjectMessage);
            outgoingMail.setProperty("body", bodyMessage);
            outgoingMail.setProperty("sentDate", new Date());
            outgoingMail.setProperty("html", Boolean.TRUE);
            outgoingMail.setProperty("pec", mailPec);

            costruisciAllegato(outgoingMail);
            // Recupera account
            String idProfile = ws.getCurrentProfile().getId();
            logger.info("RuleWFInvia - idProfile:{} ", idProfile);
            List list = executeQuery("//element(*,mailAccount)[@senders='" + idProfile + "']", null);
            if (list != null && list.size() == 1) {
                outgoingMail.setProperty("mailAccount", list.get(0));
            } else {
                throw new RuntimeException("Nessun account di posta elettronico trovato");
            }

            outgoingMail.persist();
            ws.save();

            StructuredProperty wfStr = outgoingMail.getStructuredProperty("workflow");
            String instanceId = null, taskId = null;

            List strRecords = wfStr.getRecords();

            StructuredPropertyRecord curRecord = (StructuredPropertyRecord) strRecords.get(0);
            String wrkName = "" + curRecord.getProperty("workflowName").getValue();
            instanceId = "" + curRecord.getProperty("instanceId").getValue();
            taskId = "" + curRecord.getProperty("taskId").getValue();

            String sendOutgoingMailRuleName = "SendOutgoingMailArchive";
            HashMap paramsOutgoingMail = new HashMap();
            paramsOutgoingMail.put("workSession", ws);
            List entities = new ArrayList();
            entities.add(outgoingMail);
            paramsOutgoingMail.put("entities", entities);//Action.ENTITIES_PARAMETER
            paramsOutgoingMail.put("logger", logger);
            paramsOutgoingMail.put("instanceId", instanceId);
            paramsOutgoingMail.put("taskId", taskId);
            paramsOutgoingMail.put("mailAccount", outgoingMail.getPropertyValue("mailAccount"));
            logger.info("calling externalRule " + sendOutgoingMailRuleName);
            HashMap outgoingmailRes = (HashMap) WrApiUtils.evaluateRule(ws, sendOutgoingMailRuleName, paramsOutgoingMail);
            Object tmpError = outgoingmailRes.get("globalError");
            errorMail = null;
            errorMail = (String) outgoingmailRes.get("errorMessage");
            if (errorMail != null && errorMail.equals("null")) {
                errorMail = null;
            }
            boolean error = true;
            boolean errorCons = true;
            if (tmpError != null) {
                error = ((Boolean) tmpError).booleanValue();
            }

            if (!error) {
                sendEmail = true;
                ContainedEntities originalMessage = outgoingMail.getContainedEntities("originalMessage");
                InputStream content = null;
                String contentName = null,
                        fileExtension = null;

                if (originalMessage != null && originalMessage.size() == 1) {
                    Entity message = (Entity) originalMessage.get(0);
                    List< StructuredPropertyRecord> resources = message.getStructuredProperty(Resource.RESOURCES_PROPERTY).getRecords();
                    if (resources.size() == 1) {
                        StructuredPropertyRecord record = (StructuredPropertyRecord) resources.get(0);
                        contentName = (String) record.getPropertyValue(Resource.RESOURCES_PROPERTY__ORIGINAL_FILENAME);
                        content = (InputStream) record.getPropertyValue(Resource.RESOURCES_PROPERTY__DATA);
                        fileExtension = (String) record.getPropertyValue(Resource.RESOURCES_PROPERTY__FILE_EXTENSION);
                    }
                }
                logger.info("contentName fileExtension {} {}", contentName, fileExtension);

                if (contentName != null && flagConserva) {
                    // Avvia conservazione delle mail
                    String conservazioneRuleName = "Conserva";
                    HashMap paramsConservazione = new HashMap();
                    paramsConservazione.put("workSession", ws);
                    entities = new ArrayList();
                    entities.add(outgoingMail);
                    paramsConservazione.put("entities", entities);//Action.ENTITIES_PARAMETER
                    paramsConservazione.put("logger", logger);
                    paramsConservazione.put("content", content);
                    paramsConservazione.put("contentName", contentName + "." + fileExtension);
                    logger.info("RuleWFinviaMail: Calling externalRule " + conservazioneRuleName);
                    HashMap conservazioneRes = (HashMap) WrApiUtils.evaluateRule(ws, conservazioneRuleName, paramsConservazione);

                    tmpError = conservazioneRes.get("globalError");
                    logger.info("RuleWFinviaMail: tmpError " + tmpError);

                    if (tmpError != null) {
                        errorCons = ((Boolean) tmpError).booleanValue();
                    }
                    if (!errorCons) {
                        logger.info("RuleWFinviaMail: token " + conservazioneRes.get("token"));

                        outgoingMail.setProperty("token", conservazioneRes.get("token"));
                        outgoingMail.persist();
                        ws.save();
                    } else {
                        throw new Exception("Errore in fase di conservazione della mail");
                    }
                }
            }

            String idDocumento = (String) entity.getPropertyValue("idDocumento");
            String tipoDocumento = entity.getEntityType();

            // Individua i documenti da inviare per email
            Boolean sendMail = (Boolean) entity.getPropertyValue("flagInviaMail");
            if (sendMail) {

                String entityId = outgoingMail.getId();
                String entityType = outgoingMail.getEntityType();
                // Inserisco la ricevuta anche se non va in  conserrvazione
                if ((!errorCons && !error) || (!flagConserva && !error)) {
                    String insert = "INSERT INTO WRDC_RICEVUTE (ID_DOCUMENTO, ID_WR_CHIAVE, COD_ENTITY_DOC, COD_CLASSE_DOC) "
                            + "VALUES ('" + idDocumento + "', '" + entityId + "', '" + tipoDocumento + "', '" + entityType + "')";
                    HashMap paramsInsert = new HashMap();
                    paramsInsert.put("workSession", ws);
                    paramsInsert.put("logger", logger);
                    paramsInsert.put("query", insert);
                    logger.info("calling externalRule " + updateDBArcaresRuleName);
                    WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsInsert);
                }
            }

        } catch (Exception e) {
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.debug(ExceptionUtils.getStackTrace(e));
            throw new RuntimeException("ERRORE IN RuleWFinviaMail: " + e.getMessage(), e);
        }
        return sendEmail;

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
            throw new RuntimeException("ERRORE IN RuleWFinviaMail: " + e.getMessage(), e);
        }
        return con;
    }

    private void aggiornaDocumento(boolean isOk, String idFlusso, String idDocumento, boolean principale, String errorMessage) throws Exception {
        String statoInvio = "2";
        if (!isOk) {
            statoInvio = "3";
        }
        String colonnaDesc = "DES_ERRORE_DLVRY";
        String colonnaStato = "COD_STATO_DLVRY";
        if (!principale) {
            colonnaDesc = "DES_ERR_DLVSEC";
            colonnaStato = "COD_STATO_DLVSEC";
        }

        Entity entityToUpdate = ws.getEntityById(entity.getId());
        if (principale) {
            entityToUpdate.setProperty("statoInvio", statoInvio);
        } else {
            entityToUpdate.setProperty("statoInvioSec", statoInvio);
        }
        entityToUpdate.persist();
        ws.save();
        String update = null;
        if (errorMessage == null || errorMessage.isEmpty()) {
            update = "UPDATE WRDC_DETAIL SET " + colonnaDesc + " = '', " + colonnaStato + " = '" + statoInvio + "' WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
        } else {
            update = "UPDATE WRDC_DETAIL SET " + colonnaDesc + " = '" + errorMessage + "', " + colonnaStato + " = '" + statoInvio + "' WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
        }
        HashMap paramsUpdate = new HashMap();
        paramsUpdate.put("workSession", ws);
        paramsUpdate.put("logger", logger);
        paramsUpdate.put("query", update);
        logger.info("calling externalRule " + updateDBArcaresRuleName);
        WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);

    }
}
