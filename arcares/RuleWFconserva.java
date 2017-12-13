package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;

import java.util.*;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

/**
 *
 * @author
 */
public class RuleWFconserva implements WrRuleClassBody {

    WorkSession workSession;
    Logger logger;
    Entity entity;

    HashMap conservazioneRes = null;
    private final String updateDBArcaresRuleName = "UpdateDBArcares";

    void setParameters(Map parameters) {
        workSession = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    public Object run(Map parameters) {
        setParameters(parameters);

        logger.info("RuleWFconserva: Start");

        String idFlusso = (String) entity.getPropertyValue("idFlusso");
        String idDocumento = (String) entity.getPropertyValue("idDocumento");

        boolean wasSkippingPolicies = workSession.isSkippingPolicies();
        try {
            workSession.setSkipPolicies(true);

            //----CODICE DELLA CONSERVAZIONE----
            String conservazioneRuleName = "Conserva";
            HashMap paramsConservazione = new HashMap();
            paramsConservazione.put("workSession", workSession);
            List entities = new ArrayList();
            entities.add(entity);
            paramsConservazione.put("entities", entities);//Action.ENTITIES_PARAMETER
            paramsConservazione.put("logger", logger);
            logger.info("RuleWFconserva: Calling externalRule " + conservazioneRuleName);
            conservazioneRes = (HashMap) WrApiUtils.evaluateRule(workSession, conservazioneRuleName, paramsConservazione);
            //aggiorno lo stato della conservazione
            entity.setProperty("token", conservazioneRes.get("token"));
            String update = "UPDATE WRDC_DETAIL "
                    + "SET COD_STATO_ARCH = '2', COD_ERRORE_ARCH = '', DES_ERRORE_ARCH = '' "
                    + "WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
            HashMap paramsUpdate = new HashMap();
            paramsUpdate.put("workSession", workSession);
            paramsUpdate.put("logger", logger);
            paramsUpdate.put("query", update);
            logger.info("calling externalRule " + updateDBArcaresRuleName);
            WrApiUtils.evaluateRule(workSession, updateDBArcaresRuleName, paramsUpdate);

            entity.setProperty("statoConservazione", "2");

            // Aggiornamento tabella WRDC_RICEVUTE
            // inserite per ogni ID_DOCUMENTO ...
            // ... Id e il tipo dell'entity outgoingMail 
            String entityId = entity.getId();
            String entityType = entity.getEntityType();
            String insert = "INSERT INTO SCFC01.WRDC_RICEVUTE (ID_DOCUMENTO, ID_WR_CHIAVE, COD_CLASSE_DOC, COD_ENTITY_DOC) "
                    + "VALUES ('" + idDocumento + "', '" + entityId + "', 'DocumentoGen', '" + entityType + "')";
            paramsUpdate = new HashMap();
            paramsUpdate.put("workSession", workSession);
            paramsUpdate.put("logger", logger);
            paramsUpdate.put("query", insert);
            logger.info("calling externalRule " + updateDBArcaresRuleName);
            WrApiUtils.evaluateRule(workSession, updateDBArcaresRuleName, paramsUpdate);

            ///
        } catch (Exception e) {

            String updateError = "UPDATE WRDC_DETAIL "
                    + "SET COD_STATO = '4', COD_STATO_ARCH = '3', DES_ERRORE_ARCH = 'Errore Tecnico Archiviazione'  "
                    + "WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
            HashMap paramsUpdate = new HashMap();
            paramsUpdate.put("workSession", workSession);
            paramsUpdate.put("logger", logger);
            paramsUpdate.put("query", updateError);
            logger.info("calling externalRule " + updateDBArcaresRuleName);
            WrApiUtils.evaluateRule(workSession, updateDBArcaresRuleName, paramsUpdate);

            entity.setProperty("statoConservazione", "3");
            entity.setProperty("stato", "4");
            entity.persist();

            try {
                workSession.save();
            } catch (Exception ex) {
                logger.error("error: {}", ExceptionUtils.getMessage(ex));
                logger.debug(ExceptionUtils.getStackTrace(ex));
                throw new RuntimeException("ERRORE in RuleWFconserva (ATTENZIONE STATO DELLA CONSERVAZIONE NON AGGIORNATO SU WR!!!!): " + ex.getMessage(), ex);
            }

            //stampo gli errori
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.debug(ExceptionUtils.getStackTrace(e));
            throw new RuntimeException("ERRORE in RuleWFconserva: " + e.getMessage(), e);
        } finally {
            workSession.setSkipPolicies(wasSkippingPolicies);
            entity.persist();
            workSession.save();

            logger.info("RuleWFconserva: End");
        }

        return null;
    }

}
