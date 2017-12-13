package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.QueryManager;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.rolservice.CEResult;
import it.cbt.wr.rolservice.ROLInfo;
import it.cbt.wr.rolservice.ROLServiceSoap;
import it.cbt.wr.rolservice.RecuperaInfoResult;
import it.cbt.wr.rolservice.Richiesta;
import it.cbt.wr.rolservice.client.ROLClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.apache.commons.lang3.exception.ExceptionUtils;

public class RecuperaRicevuta implements WrRuleClassBody {
    WorkSession ws; // Required
    Logger logger;	// Required
    Entity entity;	// Required
    
    void setParameters(Map parameters) {
        logger.info("RecuperaRicevute: setParameters...");
        ws = (WorkSession) parameters.get("workSession"); 
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);        
    }

    public Object run(Map parameters) {
        logger = (Logger) parameters.get("logger");
        logger.info("RecuperaRicevute: Inizio");
        setParameters(parameters);        
        boolean wasSkippingPolicies = ws.isSkippingPolicies();
         
        HashMap retMap = new HashMap();
        
        try {
            ws.setSkipPolicies(Boolean.TRUE); 
            
            String idRichiesta = (String) entity.getPropertyValue("pecUniqueId");
            String guidUtente = (String) entity.getPropertyValue("messageId");
            logger.info("idRichiesta: "+idRichiesta);
         
            StringBuilder optProt = new StringBuilder().append("/jcr:root").append("//element(*,opzioniPoste)");
            QueryManager qm = ws.getQueryManager();
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
            
            ROLClient rolClient = new ROLClient();            
            ROLServiceSoap rolService = rolClient.bindToWebServices(url + "/ROLGC/RolService.svc", user, password);
            
            Richiesta richiesta = new Richiesta();
            richiesta.setIDRichiesta(idRichiesta);
            richiesta.setGuidUtente(guidUtente);
            
            RecuperaInfoResult recuperaInfo = rolService.recuperaInfo(richiesta);
            CEResult cerVdr = recuperaInfo.getCEResult();
            logger.info(cerVdr.getType());
            logger.info(cerVdr.getCode());
            logger.info(cerVdr.getDescription());

            if (cerVdr.getType().equals("I")) {
                ROLInfo rolInfo = recuperaInfo.getROL();
                retMap.put("rolInfo", rolInfo.toString());                    
            }
            else {
                throw new RuntimeException("ERRORE IN RecuperaRicevute: " + cerVdr.getDescription());
            }
            
        } catch (Exception ex) {
            logger.warn("RecuperaRicevute Error: Executing action rule " + getClass().getName(), ex);
            logger.error("RecuperaRicevute Error: {}", ExceptionUtils.getMessage(ex));
            logger.debug(ExceptionUtils.getStackTrace(ex));
            
            throw new RuntimeException("ERRORE IN RecuperaRicevute: " + ex.getMessage(), ex);
        } finally {
            ws.setSkipPolicies(wasSkippingPolicies);    
            logger.info("RecuperaRicevute: End");
        }
        return retMap;
    }    
}

