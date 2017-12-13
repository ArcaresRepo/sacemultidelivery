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
public class RuleWFverificaErrori implements WrRuleClassBody {

    WorkSession workSession;
    Logger logger;
    Entity entity;

    private final String statoOk = "2";
    private final String updateDBArcaresRuleName = "UpdateDBArcares";

    void setParameters(Map parameters) {
        workSession = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    public Object run(Map parameters) {
        setParameters(parameters);
        HashMap retMap = new HashMap();

        logger.info("RuleWFverificaErrori - Start");
        boolean wasSkippingPolicies = workSession.isSkippingPolicies();
        try {
            workSession.setSkipPolicies(true);

            String idFlusso = (String) entity.getPropertyValue("idFlusso");
            String idDocumento = (String) entity.getPropertyValue("idDocumento");

            Boolean flagConserva = (Boolean) entity.getPropertyValue("flagConserva");
            Boolean flagFirma = (Boolean) entity.getPropertyValue("flagFirma");
            Boolean flagMarca = (Boolean) entity.getPropertyValue("flagMarca");
            Boolean flagInviaMail = (Boolean) entity.getPropertyValue("flagInviaMail");
            Boolean flagProtocolla = (Boolean) entity.getPropertyValue("flagProtocolla");

            String statoInvio = (String) entity.getPropertyValue("statoInvio");
            String statoInvioSec = (String) entity.getPropertyValue("statoInvioSec");
            String statoFirmato = (String) entity.getPropertyValue("statoFirmato");
            String statoConservazione = (String) entity.getPropertyValue("statoConservazione");
            String statoProtocolla = (String) entity.getPropertyValue("statoProtocolla");

            boolean blocca = false;
            if (flagConserva & (statoConservazione == null || !statoConservazione.equals(statoOk))) {
                blocca = true;
            }

            if (flagInviaMail & ((statoInvio != null && !statoInvio.equals(statoOk)) || (statoInvioSec != null && !statoInvioSec.equals(statoOk)))) {
                blocca = true;
            }

            if (flagFirma & (statoFirmato == null || !statoFirmato.equals(statoOk))) {
                blocca = true;
            }

            if (flagProtocolla & (statoProtocolla == null || !statoProtocolla.equals(statoOk))) {
                blocca = true;
            }
            String stato = "2";
            if (blocca) {
                stato = "3";
            }

            String update = "UPDATE WRDC_DETAIL "
                    + "SET COD_STATO = '" + stato + "' "
                    + "WHERE ID_FLUSSO = '" + idFlusso + "' AND ID_DOCUMENTO = '" + idDocumento + "'";
            HashMap paramsUpdate = new HashMap();
            paramsUpdate.put("workSession", workSession);
            paramsUpdate.put("logger", logger);
            paramsUpdate.put("query", update);
            logger.info("calling externalRule " + updateDBArcaresRuleName);
            WrApiUtils.evaluateRule(workSession, updateDBArcaresRuleName, paramsUpdate);

            entity.setProperty("stato", stato);

            entity.persist();
            workSession.save();
            retMap.put("blocca", blocca);

        } catch (Exception e) {
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.error(ExceptionUtils.getStackTrace(e));
        } finally {
            workSession.setSkipPolicies(wasSkippingPolicies);
            logger.debug("RuleWFverificaErrori - End");
        }
        return retMap;
    }

}
