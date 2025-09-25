package com.fastbee.data.controller.mobile;

import com.fastbee.common.constant.Constants;
import com.fastbee.common.core.controller.BaseController;
import com.fastbee.common.core.domain.AjaxResult;
import com.fastbee.common.core.domain.model.LoginUser;
import com.fastbee.common.enums.ThingsModelType;
import com.fastbee.common.enums.TopicType;
import com.fastbee.common.exception.ServiceException;
import com.fastbee.common.utils.gateway.mq.TopicsUtils;
import com.fastbee.common.utils.uuid.IdUtils;
import com.fastbee.iot.domain.Device;
import com.fastbee.iot.domain.DeviceLog;
import com.fastbee.iot.model.DeviceShortOutput;
import com.fastbee.iot.service.IDeviceRuntimeService;
import com.fastbee.iot.service.IDeviceService;
import com.fastbee.mqttclient.MqttClientConfig;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 移动端接入相关接口。
 *
 * <p>移动端可以通过该接口获取MQTT连接信息，以及在HTTP方式下拉取设备最新运行数据。</p>
 */
@RestController
@RequestMapping("/iot/mobile")
@Api(tags = "移动端接入")
public class MobileConnectController extends BaseController {

    private static final List<TopicType> MOBILE_FORWARD_TOPICS = Arrays.asList(
            TopicType.STATUS_POST,
            TopicType.FUNCTION_GET,
            TopicType.INFO_GET,
            TopicType.HISTORY_GET
    );

    @Resource
    private MqttClientConfig mqttClientConfig;

    @Resource
    private TopicsUtils topicsUtils;

    @Resource
    private IDeviceService deviceService;

    @Resource
    private IDeviceRuntimeService runtimeService;

    /**
     * 获取移动端MQTT连接配置。
     *
     * @return MQTT连接参数以及订阅主题
     */
    @GetMapping("/mqtt/config")
    @ApiOperation("获取移动端MQTT连接配置")
    public AjaxResult getMqttConnectConfig() {
        LoginUser loginUser = getLoginUser();
        Map<String, Object> client = buildClientConfig(loginUser);

        List<Map<String, Object>> deviceSubscriptions = new ArrayList<>();
        Set<String> aggregatedTopics = new LinkedHashSet<>();

        Device deviceFilter = new Device();
        List<DeviceShortOutput> deviceList = deviceService.selectDeviceShortList(deviceFilter);
        for (DeviceShortOutput device : deviceList) {
            Map<String, Object> deviceInfo = new HashMap<>(8);
            deviceInfo.put("deviceId", device.getDeviceId());
            deviceInfo.put("productId", device.getProductId());
            deviceInfo.put("serialNumber", device.getSerialNumber());

            List<Map<String, Object>> topics = buildDeviceTopics(device, aggregatedTopics);
            deviceInfo.put("topics", topics);
            deviceSubscriptions.add(deviceInfo);
        }

        AjaxResult result = AjaxResult.success();
        result.put("client", client);
        result.put("subscriptions", deviceSubscriptions);
        result.put("topics", aggregatedTopics.stream()
                .map(topic -> {
                    Map<String, Object> topicInfo = new HashMap<>(4);
                    topicInfo.put("topic", topic);
                    topicInfo.put("qos", 1);
                    return topicInfo;
                })
                .collect(Collectors.toList()));
        return result;
    }

    /**
     * HTTP方式获取设备运行数据。
     *
     * @param serialNumber 设备编号
     * @param type         物模型类型（默认属性）
     * @param productId    产品ID（可选，未传时根据设备自动判定）
     * @param slaveId      子设备地址（可选）
     * @return 设备运行时数据
     */
    @GetMapping("/devices/{serialNumber}/runtime")
    @ApiOperation("HTTP方式获取设备运行数据")
    public AjaxResult getRuntimeByHttp(@PathVariable("serialNumber") String serialNumber,
                                       @RequestParam(value = "type", required = false, defaultValue = "1") Integer type,
                                       @RequestParam(value = "productId", required = false) Long productId,
                                       @RequestParam(value = "slaveId", required = false) Integer slaveId) {
        DeviceShortOutput accessibleDevice = requireAccessibleDevice(serialNumber);
        Long finalProductId = productId != null ? productId : accessibleDevice.getProductId();

        ThingsModelType modelType = ThingsModelType.getType(type);
        List<DeviceLog> logs = runtimeService.runtimeBySerialNumber(serialNumber, modelType, finalProductId, slaveId);
        return AjaxResult.success(logs);
    }

    private Map<String, Object> buildClientConfig(LoginUser loginUser) {
        Map<String, Object> client = new HashMap<>(16);
        String hostUrl = mqttClientConfig.getHostUrl();
        client.put("url", hostUrl);
        try {
            URI uri = URI.create(hostUrl);
            client.put("schema", uri.getScheme());
            client.put("host", uri.getHost());
            client.put("port", uri.getPort());
        } catch (IllegalArgumentException ex) {
            client.put("schema", "tcp");
        }
        client.put("clientId", buildClientId(loginUser.getUserId()));
        client.put("username", loginUser.getUsername());
        client.put("password", Constants.TOKEN_PREFIX + loginUser.getToken());
        client.put("keepAlive", mqttClientConfig.getKeepalive());
        client.put("timeout", mqttClientConfig.getTimeout());
        client.put("clearSession", mqttClientConfig.isClearSession());
        client.put("shared", mqttClientConfig.isShared());
        client.put("sharedGroup", mqttClientConfig.isSharedGroup());
        client.put("internalBroker", mqttClientConfig.getEnabled());
        return client;
    }

    private String buildClientId(Long userId) {
        return "phone-" + userId + "-" + IdUtils.fastSimpleUUID();
    }

    private List<Map<String, Object>> buildDeviceTopics(DeviceShortOutput device, Set<String> aggregatedTopics) {
        List<Map<String, Object>> topics = new ArrayList<>();
        for (TopicType topicType : MOBILE_FORWARD_TOPICS) {
            String topicName = topicsUtils.buildTopic(device.getProductId(), device.getSerialNumber(), topicType);
            Map<String, Object> topicInfo = new HashMap<>(4);
            topicInfo.put("type", topicType.name());
            topicInfo.put("topic", topicName);
            topicInfo.put("qos", 1);
            topics.add(topicInfo);
            aggregatedTopics.add(topicName);
        }
        return topics;
    }

    private DeviceShortOutput requireAccessibleDevice(String serialNumber) {
        Device filter = new Device();
        filter.setSerialNumber(serialNumber);
        List<DeviceShortOutput> devices = deviceService.selectDeviceShortList(filter);
        if (CollectionUtils.isEmpty(devices)) {
            throw new ServiceException("设备不存在或当前用户无权限访问");
        }
        return devices.get(0);
    }
}
