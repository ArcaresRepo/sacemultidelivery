package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.api.service.workflow.WorkflowManager;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.utils.ISO9075;
import java.util.ArrayList;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

public class SaveStarterAsync implements WrRuleClassBody {

    WorkSession workSession;
    Logger logger;
    Entity entity;
    private static final String WR_FOLDER_ENTITY_TYPE = "starterContainer";
    private final String updateDBArcaresRuleName = "UpdateDBArcares"; 
    private Entity getRootFolderId() {
        List<Entity> containers = workSession.getQueryManager().executeQuery("//element(*, containers)").getResults();
        Entity rootContainer = (Entity) containers.get(0);
        return rootContainer;
    }

    private Entity getParentFolder(String baseQuery, String folderName, Entity parent) {
        Entity oFolder = null;
        String containerQuery = baseQuery + "/element(*,"
                + WR_FOLDER_ENTITY_TYPE + ")[@name='" + folderName + "']";
        logger.info("SaveStarterAsync - Query string: {}", containerQuery);

        List<Entity> entityList = workSession.getQueryManager().executeQuery(containerQuery).getResults();
        if (entityList.isEmpty()) {
            oFolder = workSession.createNewEntity(WR_FOLDER_ENTITY_TYPE, parent);
            oFolder.setProperty("name", folderName);
            oFolder.setProperty("description", folderName);
            oFolder.persist();
            workSession.save();
        } else {
            oFolder = (Entity) entityList.get(0);
        }
        return oFolder;
    }

    private Entity getDocFolder(String strAnno, String strMese) {
        String baseQuery = "/";
        Entity root = getRootFolderId();
        Entity folder = getParentFolder(baseQuery, strAnno, root);

        String parentPath = ISO9075.encodePath(folder.getPath());
        baseQuery = "/jcr:root" + parentPath;
        folder = getParentFolder(baseQuery, strMese, folder);
        return folder;
    }

    void setParameters(Map parameters) {
        workSession = (WorkSession) parameters.get("workSession");
                
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    public Object run(Map parameters) {
        setParameters(parameters);
        
        boolean wasSkippingPolicies = workSession.isSkippingPolicies();
        HashMap result = new HashMap();
        result.put("globalError", false);
        try {
            workSession.setSkipPolicies(true);
            
            logger.info("SaveStarterAsync - run : Inizio");

            if (!entity.isPersistent()) {

                entity.persist();
                workSession.save();

                Calendar today = Calendar.getInstance();
                String curYear = "" + today.get(Calendar.YEAR);
                int month = today.get(Calendar.MONTH) + 1;
                String curMonth = "" + month;

                Entity folder = getDocFolder("" + curYear, "" + curMonth);
                entity.move(folder);
                workSession.save();
                
                WorkSession cloneSession = workSession.duplicateSession();
                cloneSession.setSkipPolicies(true);
                Entity clonedEntity = cloneSession.getEntityById(entity.getId());
                
                String ruleName = "SaveStarter";
                ArrayList entitiesT = new ArrayList();
                entitiesT.add(clonedEntity);
                HashMap params = new HashMap();
                params.put("entities", entitiesT);//Action.ENTITIES_PARAMETER
                params.put("workSession", cloneSession);
                params.put("logger", logger);
                logger.info("calling externalRule " + ruleName);
                WrApiUtils.evaluateBackgroundRule(cloneSession, ruleName, params);

            } else {
                entity.persist();
                workSession.save();
            }
            logger.info("SaveStarterAsync -  run: Save performed");
            result.put("id", entity.getId());
            
        } catch (Exception ex) {
            logger.error("SaveStarterAsync -  run: Errore inatteso:" + ex + "\n StackTrace \n" + ExceptionUtils.getStackTrace(ex));
            result.put("globalError", true);
            result.put("globalError", "Errore nel salvataggio dello Starter");
        } finally {
            workSession.setSkipPolicies(wasSkippingPolicies);
            logger.info("SaveStarterAsync - run : Fine");
        }
        return result;
    }

}
