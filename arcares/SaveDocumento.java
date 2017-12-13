/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.utils.ISO9075;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;


/**
 *
 * @author Federico Mori
 */
public class SaveDocumento implements WrRuleClassBody {

    WorkSession workSession;
    Logger logger;
    Entity entity;
    private static final String WR_FOLDER_ENTITY_TYPE = "cartellaGenerica";

    void setParameters(Map parameters) {
        workSession = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    //QUESTO METODO GENERA UNA TREE DI CONTAINER E RITORNA LA FOLDER DOVE BISOGNA INSERIRE LA NUOVA ENTITY
    private Entity generateTreeInsideFolder(String strAnno, String strMese, Entity folderTarget) {
        //CREA CARTELLA ANNO
        Entity folder = generateContainerInsideFolder(strAnno, folderTarget);

        //CREA CARTELLA MESE
        folder = generateContainerInsideFolder(strMese, folder);

        return folder;
    }

    private Entity getRootFolderId() {
        List<Entity> containers = workSession.getQueryManager().executeQuery("//element(*, containers)").getResults();
        Entity rootContainer = (Entity) containers.get(0);
        return rootContainer;
    }

    //FUNZIONAMENTO METODO: CREA CARTELLA SE NON ESISTE
    //folderName : nome della cartella da creare, se non esiste.
    //folder : dove creare la cartella, se non esiste
    //RESTITUISCE LA CARTELLA FOLDERTARGET
    private Entity generateContainerInsideFolder(String folderName, Entity folderTarget) {
        Entity oFolder = null;

        String folderTargetPath = ISO9075.encodePath(folderTarget.getPath());

        String containerQuery = "/jcr:root" + folderTargetPath + "//element(*," + WR_FOLDER_ENTITY_TYPE + ")[@name='" + folderName + "']";
        logger.info("Query string: {}", containerQuery);

        List<Entity> entityList = workSession.getQueryManager().executeQuery(containerQuery).getResults();
        if (entityList.isEmpty()) {
            oFolder = workSession.createNewEntity(WR_FOLDER_ENTITY_TYPE, folderTarget);
            oFolder.setProperty("name", folderName);
            oFolder.setProperty("description", folderName);
            oFolder.persist();
            workSession.save();
        } else {
            oFolder = (Entity) entityList.get(0);
        }
        return oFolder;
    }

    public Object run(Map parameters) {
        setParameters(parameters);

        boolean wasSkippingPolicies = workSession.isSkippingPolicies();
        try {
            if (!entity.isPersistent()) {
                // Se non persistente... 
                entity.setProperty("stato", "Da Conservare");
                entity.setProperty("name", entity.getId());

                entity.persist();
                workSession.save();

            } else {
                // Se persistent ...
                entity.persist();
                workSession.save();
            }
        } catch (Exception ex) {
            logger.error("Errore nel salvataggio del documento " + entity.getId(), ex);
            throw new RuntimeException(ex);
        } finally {
            workSession.setSkipPolicies(wasSkippingPolicies);
        }

        return entity.getId();
    }

}
