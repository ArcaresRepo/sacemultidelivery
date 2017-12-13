package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.QueryManager;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.lolservice.CEResult;
import it.cbt.wr.lolservice.LOLInfo;
import it.cbt.wr.lolservice.LOLServiceSoap;
import it.cbt.wr.lolservice.RecuperaInfoResult;
import it.cbt.wr.lolservice.Richiesta;
import it.cbt.wr.lolservice.client.LOLClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.apache.commons.lang3.exception.ExceptionUtils;

public class RecuperaLettera implements WrRuleClassBody {
    WorkSession ws; // Required
    Logger logger;	// Required
    Entity entity;	// Required
    private final String updateDBArcaresRuleName = "UpdateDBArcares";
    
    void setParameters(Map parameters) {
        logger.info("RecuperaLettera: setParameters...");
        ws = (WorkSession) parameters.get("workSession"); 
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);        
    }

    public Object run(Map parameters) {
        logger = (Logger) parameters.get("logger");
        logger.info("RecuperaLettera: Inizio");
        setParameters(parameters);        
        boolean wasSkippingPolicies = ws.isSkippingPolicies();
         
        HashMap retMap = new HashMap();
        
        try {
            ws.setSkipPolicies(Boolean.TRUE); 
            
            String idRichiesta = (String) entity.getPropertyValue("pecUniqueId");
            String guidUtente = (String) entity.getPropertyValue("messageId");
            logger.info("idRichiesta: "+idRichiesta);
            
            QueryManager qm = ws.getQueryManager();
            StringBuilder optProt = new StringBuilder().append("/jcr:root").append("//element(*,opzioniPoste)");
            List<Entity> optionList = qm.executeQuery(optProt.toString()).getResults();
            Entity option;
            if(optionList.size()==1){
                 option = (Entity) optionList.get(0);
            }else{
                throw new Exception("Errore nel recupero dell'entità opzioniPoste");
            }		

            String url = (String) option.getPropertyValue("url");
            String user = (String) option.getPropertyValue("user");
            String password = (String) option.getPropertyValue("password");
            LOLClient lolClient = new LOLClient();
            
            LOLServiceSoap lolService = lolClient.bindToWebServices(url + "/LOLGC/LolService.WSDL", user, password);
            
            Richiesta richiesta = new Richiesta();
            richiesta.setIDRichiesta(idRichiesta);
            richiesta.setGuidUtente(guidUtente);

            RecuperaInfoResult recuperaInfo = lolService.recuperaInfo(richiesta);
            CEResult cerVdr = recuperaInfo.getCEResult();
            logger.info(cerVdr.getType());
            logger.info(cerVdr.getCode());
            logger.info(cerVdr.getDescription());

            if (cerVdr.getType().equals("I")) {
                LOLInfo rolInfo = recuperaInfo.getLOL();
                retMap.put("rolInfo", rolInfo.toString());                    
            }
            else {
                throw new RuntimeException("ERRORE IN RecuperaLettera: " + cerVdr.getDescription());
            }
            
        } catch (Exception ex) {
            logger.warn("RecuperaLettera Error: Executing action rule " + getClass().getName(), ex);
            logger.error("RecuperaLettera Error: {}", ExceptionUtils.getMessage(ex));
            logger.debug(ExceptionUtils.getStackTrace(ex));
            
            throw new RuntimeException("ERRORE IN RecuperaLettera: " + ex.getMessage(), ex);
        } finally {
            ws.setSkipPolicies(wasSkippingPolicies);    
            logger.info("RecuperaLettera: End");
        }
        return retMap;
    }
    
}

