package com.fastbee.mq.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fastbee.common.core.mq.DeviceReportBo;
import com.fastbee.common.utils.DateUtils;
import com.fastbee.common.utils.StringUtils;
import com.fastbee.common.utils.gateway.mq.TopicsUtils;
import com.fastbee.iot.domain.DeviceLog;
import com.fastbee.iot.model.HistoryModel;
import com.fastbee.iot.service.IDeviceLogService;
import com.fastbee.mq.model.ReportDataBo;
import com.fastbee.mq.service.IDataHandler;
import com.fastbee.mq.service.IMqttMessagePublish;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * @author gsb
 * @date 2023/2/27 14:42
 */
@Component
@Slf4j
public class DeviceOtherMsgHandler {

    @Resource
    private TopicsUtils topicsUtils;
    @Resource
    private IDataHandler dataHandler;
    @Resource
    private IMqttMessagePublish messagePublish;
    @Resource
    private IDeviceLogService deviceLogService;

    /**
     * 非属性消息消息处理入口
     * @param bo
     */
    public void messageHandler(DeviceReportBo bo){
        String type = "";
        String name = topicsUtils.parseTopicName(bo.getTopicName());
        ReportDataBo data = this.buildReportData(bo);
        switch (name) {
            case "info":
                dataHandler.reportDevice(data);
                break;
            case "ntp":
                messagePublish.publishNtp(data);
                break;
               // 接收 property/get 模拟设备数据
            case "property":
                type = topicsUtils.parseTopicName4(bo.getTopicName());
                break;
            case "function":
                data.setShadow(false);
                data.setType(2);
                data.setRuleEngine(true);
                dataHandler.reportData(data);
                break;
            case "event":
                data.setType(3);
                data.setRuleEngine(true);
                dataHandler.reportEvent(data);
                break;
            case "property-offline":
                data.setShadow(true);
                data.setType(1);
                dataHandler.reportData(data);
                break;
            case "function-offline":
                data.setShadow(true);
                data.setType(2);
                dataHandler.reportData(data);
                break;
            case "property-online":
                break;
            case "function-online":
                type = topicsUtils.parseTopicName4(bo.getTopicName());
                if (type.equals("get")) {
                    log.info("function-online:{}",bo);
                    //处理功能下发
                    messagePublish.sendFunctionMessage(bo);
                }
                break;
            case "history":
                type = topicsUtils.parseTopicName4(bo.getTopicName());
                if ("post".equals(type)) {
                    handleHistoryRequest(data);
                }
                break;
        }
    }

    /**组装数据*/
    private ReportDataBo buildReportData(DeviceReportBo bo){
        String message = new String(bo.getData());
        log.info("收到设备信息[{}]",message);
        Long productId = topicsUtils.parseProductId(bo.getTopicName());
        ReportDataBo dataBo = new ReportDataBo();
        dataBo.setMessage(message);
        dataBo.setProductId(productId);
        dataBo.setSerialNumber(bo.getSerialNumber());
        dataBo.setRuleEngine(false);
        return dataBo;
    }

    private void handleHistoryRequest(ReportDataBo data) {
        if (StringUtils.isEmpty(data.getMessage())) {
            return;
        }
        JSONObject jsonObject = JSON.parseObject(data.getMessage());
        if (jsonObject == null) {
            return;
        }
        String identity = jsonObject.getString("identity");
        if (StringUtils.isEmpty(identity)) {
            return;
        }
        String serialNumber = StringUtils.defaultIfEmpty(jsonObject.getString("serialNumber"), data.getSerialNumber());
        String endTime = jsonObject.getString("endTime");
        if (StringUtils.isEmpty(endTime)) {
            endTime = DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, DateUtils.getNowDate());
        }
        String beginTime = jsonObject.getString("startTime");
        if (StringUtils.isEmpty(beginTime)) {
            beginTime = DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS,
                    DateUtils.addDays(DateUtils.dateTime(DateUtils.YYYY_MM_DD_HH_MM_SS, endTime), -1));
        }
        DeviceLog query = new DeviceLog();
        query.setSerialNumber(serialNumber);
        query.setIdentity(identity);
        query.setBeginTime(beginTime);
        query.setEndTime(endTime);
        query.setIsHistory(1);
        query.setLogType(jsonObject.getInteger("logType") != null ? jsonObject.getInteger("logType") : 1);
        Integer isMonitor = jsonObject.getInteger("isMonitor");
        query.setIsMonitor(isMonitor != null ? isMonitor : 0);
        Integer limit = jsonObject.getInteger("limit");
        if (limit != null && limit > 0) {
            query.setTotal(limit);
        }
        Integer slaveId = jsonObject.getInteger("slaveId");
        if (slaveId != null) {
            query.setSlaveId(slaveId);
        }
        java.util.List<HistoryModel> historyList = deviceLogService.selectHistoryList(query);
        messagePublish.publishHistory(data.getProductId(), serialNumber, identity, beginTime, endTime, historyList);
    }

}
