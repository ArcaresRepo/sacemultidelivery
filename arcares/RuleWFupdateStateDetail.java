package rules.arcares;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.ContainedEntities;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.repository.entities.StructuredProperty;
import it.cbt.wr.api.service.repository.entities.StructuredPropertyRecord;
import it.cbt.wr.api.service.repository.qualities.Resource;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import java.util.HashMap;

public class RuleWFupdateStateDetail implements WrRuleClassBody {

    WorkSession ws; // Required
    Logger logger; // Required
    Entity entity; // Required

    //variabili inerenti alla connessione
    private ResultSet res;

    private static final String WR_FOLDER_ENTITY_TYPE = "cartellaGenerica";
    private final String updateDBArcaresRuleName = "UpdateDBArcares";
    void setParameters(Map parameters) {
        ws = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    public Object run(Map parameters) {
        setParameters(parameters);

        logger.info("RuleWFupdateStateDetail: Start Invia Mails");

        boolean wasSkippingPolicies = ws.isSkippingPolicies();
        try {
            ws.setSkipPolicies(true);

           
            String idFlusso = (String) entity.getPropertyValue("idFlusso");
            String idDocumento = (String) entity.getPropertyValue("idDocumento");

            //ottengo l'outgoingMail, imposto l'allegato della mail in base alla risorsa del documento
            ContainedEntities outgoingMailcontained = entity.getContainerEntity().getContainedEntities("outgoingMail");
            if (outgoingMailcontained != null && !outgoingMailcontained.isEmpty()) {
                Entity outgoingMail = (Entity) outgoingMailcontained.get(0);
                setRisorsaByEntity(outgoingMail, entity);
                outgoingMail.persist();
                ws.save();
                entity.setProperty("statoInvio", "1");
                entity.persist();
                ws.save();
                String update = "UPDATE WRDC_DETAIL "
                        + "SET COD_STATO_DLVRY = '1' "
                        + "WHERE ID_FLUSSO = '" + idFlusso + "' "
                        + "AND ID_DOCUMENTO = '" + idDocumento + "'";
                HashMap paramsUpdate = new HashMap();
                paramsUpdate.put("workSession", ws);
                paramsUpdate.put("logger", logger);
                paramsUpdate.put("query", update);
                logger.info("calling externalRule " + updateDBArcaresRuleName);
                WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
            }

            ContainedEntities postalDocContained = entity.getContainerEntity().getContainedEntities("documentoPostale");
            if (postalDocContained != null && !postalDocContained.isEmpty()) {
                Entity postalDoc = (Entity) postalDocContained.get(0);
                setRisorsaByEntity(postalDoc, entity);
                entity.setProperty("statoInvio", "1");
                entity.persist();
                ws.save();
                String update = "UPDATE WRDC_DETAIL "
                        + "SET COD_STATO_DLVRY = '1' "
                        + "WHERE ID_FLUSSO = " + idFlusso
                        + " AND ID_DOCUMENTO = '" + idDocumento + "'";
                HashMap paramsUpdate = new HashMap();
                paramsUpdate.put("workSession", ws);
                paramsUpdate.put("logger", logger);
                paramsUpdate.put("query", update);
                logger.info("calling externalRule " + updateDBArcaresRuleName);
                WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
            }

//			//SELECT
//			//controllo i rimanenti documenti che rimangono da elaborare
//			String select = "SELECT * FROM WRDC_DETAIL "+
//							"WHERE ID_FLUSSO = " + idFlusso + 
//							" AND COD_STATO_DLVRY = '0' AND FLG_DELIVERY <> 'N'";
//			res = selectStatemant.executeQuery(select);
//			//se sono l'ultimo (se non ce ne sono vuol dire che sono l'ultimo), eseguo la signal dell'outgoing mail.
//			if(!res.next())
//			{
//				StructuredProperty wfHeader = entity.getContainerEntity().getStructuredProperty("workflow");
//				WorkflowManager wfManager = workSession.getWorkflowManager();
//				List <StructuredPropertyRecord> strRecords = wfHeader.getRecords();
//				StructuredPropertyRecord curRecord = (StructuredPropertyRecord)strRecords.get(0);
//				String originalFileName = (String)curRecord.getProperty("workflowName").getValue();
//				String curInstanceId = (String)curRecord.getProperty("instanceId").getValue();
//				logger.debug("Signal Workflow at " + entity.getContainerEntity().getPropertyValue("name"));
//				
//				wfManager.signalMessage(curInstanceId, "invia");
//				//--------fa solo la signal--------
//			}
        } catch (Exception e) {
            //aggiorno il campo stato in errore
            String updateError = "UPDATE WRDC_DETAIL "
                    + "SET COD_STATO_DLVRY = '3' "
                    + "WHERE ID_FLUSSO = " + entity.getPropertyValue("idFlusso")
                    + " AND ID_DOCUMENTO = '" + entity.getPropertyValue("idDocumento") + "'";
            HashMap paramsUpdate = new HashMap();
            paramsUpdate.put("workSession", ws);
            paramsUpdate.put("logger", logger);
            paramsUpdate.put("query", updateError);
            logger.info("calling externalRule " + updateDBArcaresRuleName);
            WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
           
            //stampo gli errori
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.debug(ExceptionUtils.getStackTrace(e));
            throw new RuntimeException("ERRORE in RuleWFupdateStateDetail: " + e.getMessage(), e);
        } finally {
            if (res != null) {
                try {
                    res.close();
                } catch (SQLException e) {
                    logger.error("error: {}", ExceptionUtils.getMessage(e));
                    logger.debug(ExceptionUtils.getStackTrace(e));
                }
            }
            
            ws.setSkipPolicies(wasSkippingPolicies);
        }
        logger.info("RuleWFupdateStateDetail:  Finish Invia Mails");

        return null;
    }

    private void setRisorsaByEntity(Entity entityTarget, Entity entitySource) {
        //ottengo la proprietà strutturata delle risorse dell'entityTarget e dell'entitySource
        StructuredProperty entityTargetResources = entityTarget.getStructuredProperty(Resource.RESOURCES_PROPERTY);
        StructuredProperty entitySourceResources = entitySource.getStructuredProperty(Resource.RESOURCES_PROPERTY);

        //ottengo la risorsa dall'entitySource
        List<StructuredPropertyRecord> entitySourceResourcesRecords = entitySourceResources.getRecords();
        for (int cont = 0; cont < entitySourceResourcesRecords.size(); cont++) {
            //creo una nuovo record nell'entityTarget per ogni risorsa dell'entitySource
            StructuredPropertyRecord newRecord = entityTargetResources.createRecord();

            //la imposto con i dati del record dell'altra entity
            newRecord.setProperty("fileExtension", ((StructuredPropertyRecord) entitySourceResourcesRecords.get(cont)).getPropertyValue("fileExtension"));
            newRecord.setProperty("description", ((StructuredPropertyRecord) entitySourceResourcesRecords.get(cont)).getPropertyValue("description"));
            newRecord.setProperty("data", ((StructuredPropertyRecord) entitySourceResourcesRecords.get(cont)).getPropertyValue("data"));
            newRecord.setProperty("originalFilename", ((StructuredPropertyRecord) entitySourceResourcesRecords.get(cont)).getPropertyValue("originalFilename"));
            newRecord.setProperty("mimeType", ((StructuredPropertyRecord) entitySourceResourcesRecords.get(cont)).getPropertyValue("mimeType"));

            //la aggiungo
            entityTargetResources.addRecord(newRecord);
        }
    }
}
