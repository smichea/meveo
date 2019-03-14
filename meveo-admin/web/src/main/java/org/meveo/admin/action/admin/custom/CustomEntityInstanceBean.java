package org.meveo.admin.action.admin.custom;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.faces.view.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.jboss.seam.international.status.builder.BundleKey;
import org.meveo.admin.action.CustomFieldBean;
import org.meveo.admin.exception.BusinessException;
import org.meveo.admin.web.interceptor.ActionMethod;
import org.meveo.elresolver.ELException;
import org.meveo.model.crm.CustomFieldTemplate;
import org.meveo.model.crm.custom.CustomFieldTypeEnum;
import org.meveo.model.customEntities.CustomEntityInstance;
import org.meveo.model.customEntities.CustomEntityTemplate;
import org.meveo.service.base.local.IPersistenceService;
import org.meveo.service.crm.impl.CustomFieldInstanceService;
import org.meveo.service.custom.CustomEntityInstanceService;
import org.meveo.service.custom.CustomEntityTemplateService;
import org.meveo.service.neo4j.base.Neo4jConnectionProvider;
import org.meveo.service.neo4j.service.Neo4jService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Named
@ViewScoped
public class CustomEntityInstanceBean extends CustomFieldBean<CustomEntityInstance> {

    Logger log = LoggerFactory.getLogger(CustomEntityInstanceBean.class);

    private static final long serialVersionUID = -459772193950603406L;

    private String customEntityTemplateCode;

    private CustomEntityTemplate customEntityTemplate;

    public CustomEntityInstanceBean() {
        super(CustomEntityInstance.class);
    }

    @Inject
    private CustomEntityInstanceService customEntityInstanceService;

    @Inject
    private CustomEntityTemplateService customEntityTemplateService;

    @Inject
    private CustomFieldInstanceService customFieldInstanceService;

    @Inject
    private Neo4jService neo4jService;

    @Inject
    private Neo4jConnectionProvider neo4jConnectionProvider;

    @Override
    protected IPersistenceService<CustomEntityInstance> getPersistenceService() {
        return customEntityInstanceService;
    }

    public void setCustomEntityTemplateCode(String customEntityTemplateCode) {
        this.customEntityTemplateCode = customEntityTemplateCode;
    }

    public String getCustomEntityTemplateCode() {
        return customEntityTemplateCode;
    }

    @Override
    public CustomEntityInstance initEntity() {

        CustomEntityInstance initResult = super.initEntity();

        // If it is a new entity and does not have yet the CET code set yet, set it from request parameter and initialize custom fields
        if (initResult.getCetCode() == null && customEntityTemplateCode != null) {
            initResult.setCetCode(customEntityTemplateCode);
        }
        
        if (customEntityTemplateCode==null && !initResult.isTransient()){
            customEntityTemplateCode = initResult.getCetCode();
        }
        
        return initResult;
    }

    @Override
    @ActionMethod
    public String saveOrUpdate(boolean killConversation) throws BusinessException, ELException {
        if (StringUtils.isBlank(entity.getCetCode())) {
            messages.error(new BundleKey("messages", "customEntityInstance.noCetCodeSet"));
            return null;
        }

        // Check for unicity of code
        CustomEntityInstance ceiSameCode = customEntityInstanceService.findByCodeByCet(entity.getCetCode(), entity.getCode());
        if ((entity.isTransient() && ceiSameCode != null)
                || (!entity.isTransient() && ceiSameCode != null && ceiSameCode.getId() != null && entity.getId().longValue() != ceiSameCode.getId().longValue())) {
            messages.error(new BundleKey("messages", "commons.uniqueField.code"));
            return null;
        }
        String listViewName =  super.saveOrUpdate(killConversation);
        ceiSameCode = customEntityInstanceService.findByCodeByCet(entity.getCetCode(), entity.getCode());
        Map<String, Object> fieldValues = new HashMap<>();
        Map<String, CustomFieldTemplate> customFieldTemplates = customFieldTemplateService.findByAppliesTo(ceiSameCode);
        for (Iterator<CustomFieldTemplate> iterator = customFieldTemplates.values().iterator(); iterator.hasNext(); ) {
            CustomFieldTemplate cft = iterator.next();
            if (cft.getFieldType() != CustomFieldTypeEnum.CHILD_ENTITY) {
                Object value = customFieldInstanceService.getCFValue(ceiSameCode, cft.getCode());
                log.info("value : {}", value);
                log.info("Code of cft : {}", cft.getCode());
                fieldValues.put(cft.getCode(), value);
            }
        }

        if (!fieldValues.isEmpty()) {
            log.info("fieldValues : {}", fieldValues);

            /*
            * TODO: Add parameter to ask user in which repository he wants to put it.
            * Could be :
            * - One repository
            * - All repostitories (default)
            * - Default repository - Implemented
            */
            /****RAY : i comented out this call, bacause the save in NEO4j must be optional and only for some specific CFT****************/
           // neo4jService.addCetNode(null, entity.getCetCode(), fieldValues);
        }


        return listViewName;
    }

    @Override
    public String getEditViewName() {
        return "customEntity";
    }

    @Override
    public String getListViewName() {
        return "customEntities";
    }

    public CustomEntityTemplate getCustomEntityTemplate() {
        if (customEntityTemplate == null && customEntityTemplateCode != null) {
            customEntityTemplate = customEntityTemplateService.findByCode(customEntityTemplateCode);
        }
        return customEntityTemplate;

    }

    @Override
    protected Map<String, Object> supplementSearchCriteria(Map<String, Object> searchCriteria) {

        searchCriteria.put("cetCode", customEntityTemplateCode);
        return searchCriteria;
    }
}