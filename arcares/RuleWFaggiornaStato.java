package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;

import java.util.*;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

public class RuleWFaggiornaStato implements WrRuleClassBody {

    WorkSession ws;
    Logger logger;
    Entity entity;

    private String update;
    private final String updateDBArcaresRuleName = "UpdateDBArcares";
    void setParameters(Map parameters) {
        ws = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    public Object run(Map parameters) {
        setParameters(parameters);

        logger.info("RuleWFaggiornaStato - Start");
        boolean wasSkippingPolicies = ws.isSkippingPolicies();
        try {
            
            List documenti = entity.getContainedEntities("documento");

            String codStatoHeader = "2";
            for (int cont = 0; cont < documenti.size(); cont++) {
                Entity entDocument = (Entity) documenti.get(cont);

                String codStatoDoc = "2";
             
                Boolean flagConserva = (Boolean) entity.getPropertyValue("flagConserva");
                Boolean flagFirma = (Boolean) entity.getPropertyValue("flagFirma");
                Boolean flagInviaMail = (Boolean) entity.getPropertyValue("flagInviaMail");
                Boolean flagProtocolla = (Boolean) entity.getPropertyValue("flagProtocolla");

                String statoInvio = (String) entity.getPropertyValue("statoInvio");
                String statoFirmato = (String) entity.getPropertyValue("statoFirmato");
                String statoConservazione = (String) entity.getPropertyValue("statoConservazione");
                String statoProtocolla = (String) entity.getPropertyValue("statoProtocolla");

                boolean blocca = false;
                if(flagConserva & !statoConservazione.equals(codStatoDoc)){
                    blocca = true;
                }

                if(flagInviaMail & !statoInvio.equals(codStatoDoc)){
                    blocca = true;
                }

                if(flagFirma & !statoFirmato.equals(codStatoDoc)){
                    blocca = true;
                }
            
                if(flagProtocolla & !statoProtocolla.equals(codStatoDoc)){
                    blocca = true;
                }            

                if (blocca) {
                    entDocument.setProperty("stato", "3");
                    codStatoDoc = "3";
                    codStatoHeader = "3";
                } else if (blocca) {
                    entDocument.setProperty("stato", "2");
                    codStatoDoc = "2";
                    if (codStatoHeader.equals("2")) {
                        codStatoHeader = "2";
                    }
                }
                update = "UPDATE WRDC_DETAIL "
                        + "SET COD_STATO = '" + codStatoDoc + "' "
                        + "WHERE ID_FLUSSO = '" + entDocument.getPropertyValue("idFlusso") + "' "
                        + "AND ID_DOCUMENTO ='" + entDocument.getPropertyValue("idDocumento") + "'";
               
                HashMap paramsUpdate = new HashMap();
                paramsUpdate.put("workSession", ws);
                paramsUpdate.put("logger", logger);
                paramsUpdate.put("query", update);
                logger.info("calling externalRule " + updateDBArcaresRuleName);
                WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
                
                entDocument.persist();
                ws.save();
            }
            entity.setProperty("stato", codStatoHeader);
            update = "UPDATE WRDC_HEADER "
                    + "SET COD_STATO = '" + codStatoHeader + "' "
                    + "WHERE ID_FLUSSO = '" + entity.getPropertyValue("idFlusso") + "' ";
            
            HashMap paramsUpdate = new HashMap();
            paramsUpdate.put("workSession", ws);
            paramsUpdate.put("logger", logger);
            paramsUpdate.put("query", update);
            logger.info("calling externalRule " + updateDBArcaresRuleName);
            WrApiUtils.evaluateRule(ws, updateDBArcaresRuleName, paramsUpdate);
                
            entity.persist();
            ws.save();
        } catch (Exception e) {
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.debug(ExceptionUtils.getStackTrace(e));
        } finally {
            //chiudo lo statement che gestisce gli update
            
            ws.setSkipPolicies(wasSkippingPolicies);
            logger.debug("RuleWFaggiornaStato - End");
        }
        return null;
    }

}
