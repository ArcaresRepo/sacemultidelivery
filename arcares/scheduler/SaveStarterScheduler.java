package rules.arcares.scheduler;

import it.cbt.camel.processor.scriptexecutor.ScriptProcessor;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.ws.rest.client.RESTServiceClient;
import it.cbt.wr.ws.rest.client.RESTServiceClientFactory;

import it.cbt.wr.ws.model.EntityBean;
import it.cbt.wr.ws.model.SingleActionBean;


import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;

import java.util.Map;
import java.net.URL;
import java.util.Calendar;

/**
 *
 * @author Mori
 */
public class SaveStarterScheduler implements WrRuleClassBody {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("it.cbt.scheduler");

    private RESTServiceClient restServiceClient = null;

    private static final String WR_SAVE_ACTION_NAME = "saveStarterAsync";
    
    public Object run(Map parameters) {
        try {
            if (!parameters.containsKey(ScriptProcessor.PREVIOUS_STEP_RESULT)) {
                throw new RuntimeException("No contents to process");
            }
            String entityClassName = (String) parameters.get("classType");
            LOGGER.info("Entity class type: {}", entityClassName);
            
            Calendar c = Calendar.getInstance();
            
            initServices(parameters);
            EntityBean docEntityBean = new EntityBean();
            docEntityBean.setEntityType(entityClassName);
            docEntityBean.setPropertyValue("name", "" + c.getTimeInMillis());
            docEntityBean.setPropertyValue("taskAutomatico", true);
            
            SingleActionBean singleAction = new SingleActionBean(WR_SAVE_ACTION_NAME, docEntityBean);
            String wrEntityId = restServiceClient.executeInsertAction(singleAction);
            if(wrEntityId == null){
                LOGGER.error("SaveStarterScheduler - Errore nella creazione dell'entità starter");
            }

        } catch (Exception ex) {
            LOGGER.error("SaveStarterScheduler - Error processing invoice ", ex);
            ex.printStackTrace();
        } finally {
            if (restServiceClient != null && restServiceClient.isConnected()) {
                restServiceClient.disconnect();
                LOGGER.info("SaveStarterScheduler - WR service disconnected");
            }
           
        }
        return null;
    }

    private void initServices(Map parameters) throws Exception {
        URL wrServiceURL = new URL((String) parameters.get("wrRESTEndpointURL"));
        LOGGER.info("SaveStarterScheduler - Connecting to WR REST service at {}", wrServiceURL.toString());
        String RESTClientBuildVersion = null;
        restServiceClient = RESTServiceClientFactory.getClientInstance(MediaType.APPLICATION_JSON_TYPE, RESTClientBuildVersion);

        LOGGER.info("SaveStarterScheduler - WR REST service is up and working");

        String wrUsername = (String) parameters.get("wrUsername");
        String wrPassword = (String) parameters.get("wrPassword");
        String wrWorkspace = (String) parameters.get("wrWorkspace");

        restServiceClient.setMaintainSession(true);
        restServiceClient.setWsAddress(wrServiceURL.toString());

        LOGGER.info("SaveStarterScheduler - Connecting to wr service at {} ", wrServiceURL.toString());
        LOGGER.info("SaveStarterScheduler - Username: {}", wrUsername);
        LOGGER.info("SaveStarterScheduler - Password: {}", wrPassword);
        LOGGER.info("SaveStarterScheduler - Workspace: {}", wrWorkspace);
        restServiceClient.connect();
        restServiceClient.login(wrUsername, wrPassword, wrWorkspace);
        restServiceClient.setMaintainSession(true);
        LOGGER.info("SaveStarterScheduler - WR service is up and connected");
    }

}