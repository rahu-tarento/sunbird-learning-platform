package org.sunbird.content.mgr.impl.operation.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.common.Platform;
import org.sunbird.common.dto.Request;
import org.sunbird.common.dto.Response;
import org.sunbird.common.enums.TaxonomyErrorCodes;
import org.sunbird.common.exception.ClientException;
import org.sunbird.common.exception.ResponseCode;
import org.sunbird.common.exception.ServerException;
import org.sunbird.common.util.HttpRestUtil;
import org.sunbird.graph.cache.util.RedisStoreUtil;
import org.sunbird.graph.common.DateUtils;
import org.sunbird.graph.dac.enums.GraphDACParams;
import org.sunbird.graph.dac.model.Node;
import org.sunbird.graph.engine.router.GraphEngineManagers;
import org.sunbird.learning.common.enums.ContentAPIParams;
import org.sunbird.learning.common.enums.ContentErrorCodes;
import org.sunbird.searchindex.elasticsearch.ElasticSearchUtil;
import org.sunbird.searchindex.util.CompositeSearchConstants;
import org.sunbird.taxonomy.mgr.impl.BaseContentManager;
import org.sunbird.telemetry.dto.Telemetry;
import org.sunbird.telemetry.logger.TelemetryManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class RetireOperation extends BaseContentManager {

    private static String COMPOSITE_SEARCH_URL = Platform.config.getString("kp.search_service.base_url") +"/v3/search";
    private static ObjectMapper mapper = new ObjectMapper();
    /**
     * @param contentId
     * @return
     */
    public Response retire(String contentId) {
        System.out.println(">>>>>>>>>>>> retire API called");
        TelemetryManager.error("<<<<<<<<<< retire content id: " + contentId);

        Boolean isImageNodeExist = false;
        if (StringUtils.isBlank(contentId) || StringUtils.endsWithIgnoreCase(contentId, DEFAULT_CONTENT_IMAGE_OBJECT_SUFFIX))
            throw new ClientException(ContentErrorCodes.ERR_INVALID_CONTENT_ID.name(),
                    "Please Provide Valid Content Identifier.");

        Response response = getDataNode(TAXONOMY_ID, contentId);
        if (checkError(response))
            return response;

        Node node = (Node) response.get(GraphDACParams.node.name());
        System.out.println(">>>>>>>>> Node details: " + node);
        String mimeType = (String) node.getMetadata().get(ContentAPIParams.mimeType.name());
        String status = (String) node.getMetadata().get(ContentAPIParams.status.name());

        System.out.println(">>>>>>>>> status: " + status);

        if (StringUtils.equalsIgnoreCase(ContentAPIParams.Retired.name(), status)) {
            throw new ClientException(ContentErrorCodes.ERR_CONTENT_RETIRE.name(),
                    "Content with Identifier [" + contentId + "] is already Retired.");
        }

        Boolean isCollWithFinalStatus = (StringUtils.equalsIgnoreCase("application/vnd.ekstep.content-collection", mimeType) && finalStatus.contains(status)) ? true : false;
        RedisStoreUtil.delete(contentId);
        Response imageNodeResponse = getDataNode(TAXONOMY_ID, getImageId(contentId));
        if (!checkError(imageNodeResponse))
            isImageNodeExist = true;

        List<String> identifiers = (isImageNodeExist) ? Arrays.asList(contentId, getImageId(contentId)) : Arrays.asList(contentId);

        System.out.println(">>>>>>>>>>>>>>>>>> isCollWithFinalStatus: " + isCollWithFinalStatus);

        if (isCollWithFinalStatus) {
            List<String> shallowIds = getShallowCopy(node.getIdentifier());
            System.out.println(">>>>>>>>>> shallowIds: " + shallowIds);

            if(CollectionUtils.isNotEmpty(shallowIds))
                throw new ClientException(ContentErrorCodes.ERR_CONTENT_RETIRE.name(),
                        "Content With Identifier [" + contentId + "] Can Not Be Retired. It Has Been Adopted By Other Users.");
            RedisStoreUtil.delete(COLLECTION_CACHE_KEY_PREFIX + contentId);
            Response hierarchyResponse = getCollectionHierarchy(contentId);

            System.out.println(">>>>>>>>>>> hierarchyResponse: " + hierarchyResponse);

            if (checkError(hierarchyResponse)) {
                throw new ServerException(ContentErrorCodes.ERR_CONTENT_RETIRE.name(),
                        "Unable to fetch Hierarchy for Root Node: [" + contentId + "]");
            }
            Map<String, Object> rootHierarchy = (Map<String, Object>) hierarchyResponse.getResult().get("hierarchy");
            System.out.println(">>>>>>>>>>>> rootHierarchy: " + rootHierarchy);
            List<Map<String, Object>> rootChildren = (List<Map<String, Object>>) rootHierarchy.get("children");
            System.out.println(">>>>>>>>>> rootChildren: " + rootChildren);
            List<String> childrenIdentifiers = new ArrayList<String>();
            getChildrenIdentifiers(childrenIdentifiers, rootChildren);

            if (CollectionUtils.isNotEmpty(childrenIdentifiers)) {
                String[] unitIds = childrenIdentifiers.stream().map(id -> (COLLECTION_CACHE_KEY_PREFIX + id)).collect(Collectors.toList()).toArray(new String[childrenIdentifiers.size()]);
                System.out.println(">>>>>>>>>>>> unitIds: " + unitIds);

                if (unitIds.length > 0)
                    RedisStoreUtil.delete(unitIds);
            }
            try {
                System.out.println(">>>>>>>>>>> search index name: " + CompositeSearchConstants.COMPOSITE_SEARCH_INDEX);
                ElasticSearchUtil.bulkDeleteDocumentById(CompositeSearchConstants.COMPOSITE_SEARCH_INDEX, CompositeSearchConstants.COMPOSITE_SEARCH_INDEX_TYPE, childrenIdentifiers);
            } catch (Exception e) {
                TelemetryManager.error("Exception Occured While Removing Children's from ES | Exception is : " + e);
                throw new ServerException(ContentErrorCodes.ERR_CONTENT_RETIRE.name(), "Something Went Wrong While Removing Children's from ES.");
            }
        }

        String date = DateUtils.formatCurrentDate();
        Map<String, Object> params = new HashMap<>();
        params.put("status", "Retired");
        params.put("lastStatusChangedOn", date);
        params.put("lastUpdatedOn", date);
        response = updateDataNodes(params, identifiers, TAXONOMY_ID);
        if (checkError(response)) {
            return response;
        }
        if (isCollWithFinalStatus)
            deleteHierarchy(Arrays.asList(contentId));

        Response res = getSuccessResponse();
        res.put(ContentAPIParams.node_id.name(), contentId);
        return res;
    }

    private List<String> getShallowCopy(String identifier) {
        List<String> result = new ArrayList<String>();
        Map<String, Object> reqMap = getSearchRequest(identifier);
        try {
            Response searchResponse = HttpRestUtil.makePostRequest(COMPOSITE_SEARCH_URL, reqMap, new HashMap<String, String>());
            if (searchResponse.getResponseCode() == ResponseCode.OK && MapUtils.isNotEmpty(searchResponse.getResult())) {
                Map<String, Object> searchResult = searchResponse.getResult();
                TelemetryManager.log("Retire Shallow search result log" + searchResult.get("count") + searchResult.get("content"));
                Integer count = (Integer) searchResult.getOrDefault("count", 0);
                if (count > 0) {
                    ((List<Map<String, Object>>) searchResult.getOrDefault("content", new ArrayList<Map<String, Object>>())).forEach(res -> {
                        try {
                            Map<String, Object> originData = mapper.readValue((String) res.get("originData"), new TypeReference<Map<String, Object>>() {});
                            if (StringUtils.equalsIgnoreCase((String) originData.get("copyType"), "shallow") && !StringUtils.equalsIgnoreCase((String) res.get("status"), "Retired")) {
                                result.add((String) res.get("identifier"));
                            }
                        } catch (Exception e) {
                            TelemetryManager.error("Something went wrong when fetching shallow copied contents, origin data for id: " + identifier);
                            throw new ServerException(TaxonomyErrorCodes.SYSTEM_ERROR.name(),
                                    "Something Went Wrong While Processing Your Request. Please Try Again After Sometime!");                        }
                    });
                }
            } else {
                TelemetryManager.info("Recevied Invalid Search Response For Shallow Copy. Response is : " + searchResponse);
                throw new ServerException(TaxonomyErrorCodes.SYSTEM_ERROR.name(),
                        "Something Went Wrong While Processing Your Request. Please Try Again After Sometime!");
            }
        } catch (Exception e) {
            TelemetryManager.error("Exception Occurred While Making Search Call for Shallow Copy Validation. Exception is ", e);
            throw new ServerException(TaxonomyErrorCodes.SYSTEM_ERROR.name(),
                    "Something Went Wrong While Processing Your Request. Please Try Again After Sometime!");
        }
        return result;
    }

    private  Map<String, Object> getSearchRequest(String identifier) {
        Map<String, Object> requestMap = new HashMap<String, Object>();
        Map<String, Object> filters = new HashMap<String, Object>();
        filters.put("objectType", "Content");
        filters.put("status", Arrays.asList());
        filters.put("origin", identifier);
        requestMap.put("filters", filters);
        requestMap.put("exists", Arrays.asList("originData"));
        requestMap.put("fields", Arrays.asList("identifier", "originData", "status"));
        Map<String, Object> request = new HashMap<String, Object>();
        request.put("request", requestMap);
        return request;
    }

    /**
     * @param map
     * @param idList
     * @param graphId
     * @return
     */
    private Response updateDataNodes(Map<String, Object> map, List<String> idList, String graphId) {
        Response response;
        TelemetryManager.log("Getting Update Node Request For Node ID: " + idList);
        Request updateReq = getRequest(graphId, GraphEngineManagers.NODE_MANAGER, "updateDataNodes");
        updateReq.put(GraphDACParams.node_ids.name(), idList);
        updateReq.put(GraphDACParams.metadata.name(), map);
        TelemetryManager.log("Updating DialCodes for :" + idList);
        response = getResponse(updateReq);
        TelemetryManager.log("Returning Node Update Response.");
        return response;
    }

    /**
     *
     * @param unitNodes
     * @param children
     */
    private void getChildrenIdentifiers(List<String> unitNodes, List<Map<String, Object>> children) {
        if(CollectionUtils.isNotEmpty(children)) {
            children.stream().forEach(child -> {
                if(StringUtils.equalsIgnoreCase("Parent", (String) child.get("visibility"))) {
                    unitNodes.add((String)child.get("identifier"));
                    getChildrenIdentifiers(unitNodes, (List<Map<String, Object>>) child.get("children"));
                }
            });
        }
    }

}
