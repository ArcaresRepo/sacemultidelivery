package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.utils.ISO9075;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

public class SaveHeader implements WrRuleClassBody {

    WorkSession workSession;
    Logger logger;
    Entity entity;
    private static final String WR_FOLDER_ENTITY_TYPE = "starterContainer";

    private Entity getRootFolderId() {
        List<Entity> containers = workSession.getQueryManager().executeQuery("//element(*, containers)").getResults();
        Entity rootContainer = (Entity) containers.get(0);
        return rootContainer;
    }

    private Entity getParentFolder(String baseQuery, String folderName, Entity parent) {
        Entity oFolder = null;
        String containerQuery = baseQuery + "/element(*,"
                + WR_FOLDER_ENTITY_TYPE + ")[@name='" + folderName + "']";
        logger.info("Query string: {}", containerQuery);

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
        try {
            workSession.setSkipPolicies(true);
            logger.info("SaveHeader - run : Inizio");

            entity.persist();
            workSession.save();
            logger.info("SaveHeader -  run: Save performed");
        } catch (Exception ex) {
            logger.error("SaveHeader -  run: Errore inatteso:" + ex + "\n StackTrace \n" + ExceptionUtils.getStackTrace(ex));
            throw new RuntimeException(ex);
        } finally {
            workSession.setSkipPolicies(wasSkippingPolicies);
            logger.info("SaveHeader - run : Fine");
        }
        return entity.getId();
    }
}
