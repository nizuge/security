package cn.anytec.security.component;

import cn.anytec.security.config.GeneralConfig;
import cn.anytec.security.findface.FindFaceService;
import cn.anytec.security.findface.model.*;
import cn.anytec.security.model.TbCamera;
import cn.anytec.security.model.TbPerson;
import cn.anytec.security.model.parammodel.FindFaceParam;
import cn.anytec.security.model.parammodel.IdenfitySnapParam;
import cn.anytec.security.component.mongo.MongoDBService;
import cn.anytec.security.service.CameraService;
import cn.anytec.security.service.PersonService;
import cn.anytec.security.model.websocketmodel.FdSnapShot;
import cn.anytec.security.model.websocketmodel.FrWarning;
import cn.anytec.security.model.parammodel.TimeModel;
import cn.anytec.security.websocket.WSSendHandler;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import java.text.SimpleDateFormat;
import java.util.*;


/**
 * Created by zhao on 2018/7/19.
 */
@Component
public class FRDataHandler {
    private static final Logger logger = LoggerFactory.getLogger(FRDataHandler.class);
    @Autowired
    GeneralConfig config;
    @Autowired
    private FindFaceService findFaceService;
    @Autowired
    private MongoDBService mongoDBService;
    @Autowired
    private PersonService personService;
    @Autowired
    private CameraService cameraService;
    @Autowired
    private WSSendHandler wsSendHandler;

    public void recieveSnap(String cameraSdkId, String timestamp, String bbox, MultipartFile photo) {
        TimeModel timeModel = new TimeModel(timestamp);
        List<IdentifyFace> faceList = addFace(photo,cameraSdkId,bbox);
        if (!CollectionUtils.isEmpty(faceList)) {
            TbCamera camera = cameraService.getCameraBySdkId(cameraSdkId);
            faceList.forEach(face -> {
                handleSnapshot(face, timeModel, camera);
                IdentifyPojo identifyPojo = identifyInStaticGallery(photo);
                if (identifyPojo != null) {
                    handleWarningSnap(identifyPojo,face, timeModel, camera);
                }
            });
        }
    }

    //快照入sdk动态库
    public List<IdentifyFace> addFace(MultipartFile photo, String cameraSdkId, String bbox) {
        FindFaceParam params = new FindFaceParam();
        params.setMeta(cameraSdkId);
        params.setBbox(bbox);
        String[] galleries = new String[]{config.getSnapGallery()};
        params.setGalleries(galleries);
        try {
            FacePojo facePojo = findFaceService.addFace(photo.getBytes(), params);
            if (facePojo != null) {
                return facePojo.getResults();
            } else {
                logger.info("图片入动态库失败！");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    //快照处理
    public void handleSnapshot(IdentifyFace face, TimeModel timeModel, TbCamera camera) {
        String catchTime = timeModel.getCatchTime();
        logger.info("快照获取：" + catchTime);
        //快照推送
        FdSnapShot snapShot = new FdSnapShot();
        snapShot.setCatchTime(catchTime);
        snapShot.setSnapshotUrl(face.getThumbnail());
        wsSendHandler.sendSnapShot(snapShot, camera.getSdkId());
        //快照存入mongo
        Map<String, Object> snapshotAddition = insertCameraData(camera);
        snapshotAddition.put("photoUrl",face.getPhoto());
        snapshotAddition.put("faceSdkId", face.getId());
        snapshotAddition.put("gender",face.getGender());
        String age = face.getAge().toString().split("\\.")[0];
        snapshotAddition.put("age",age);
        snapshotAddition.put("emotions",face.getEmotions());
        snapshotAddition.put("timestamp", timeModel.getTimestamp());
        mongoDBService.addSnapShot(JSONObject.toJSONString(snapShot), snapshotAddition);
        //今日抓拍数推送
        sendSnapshotTimes(timeModel);
    }

    public Map<String, Object> insertCameraData(TbCamera camera) {
        Map<String, Object> map = new HashMap<>();
        map.put("cameraId", camera.getId());
        map.put("cameraSdkId",camera.getSdkId());
        map.put("cameraGroupId", camera.getGroupId());
//        map.put("location",camera.getLocation());
        map.put("location", returnLocation());
        return map;
    }

    //临时方法，到时候以真正的camera的location为准
    public String returnLocation() {
        List<String> locationList = new ArrayList<>();
        locationList.add("114.056215,22.539968");
        locationList.add("114.062931,22.542524");
        locationList.add("114.062524,22.540166");
        locationList.add("114.052996,22.536698");
        locationList.add("114.068124,22.544685");
        locationList.add("114.044585,22.546607");
        locationList.add("114.044714,22.539631");
        locationList.add("114.049005,22.554653");
        locationList.add("114.045229,22.512397");
        locationList.add("114.067459,22.510019");
        locationList.add("114.086513,22.534993");
        locationList.add("114.050121,22.524132");
        locationList.add("114.104366,22.546171");
        Integer i = new Random().nextInt(locationList.size());
        return locationList.get(i);
    }

    //推送今日抓拍快照次数
    public void sendSnapshotTimes(TimeModel timeModel) {
        int hour = timeModel.getHour();
        int minute = timeModel.getMinute();
        long snapshot_start_timestamp = System.currentTimeMillis() - 1000 * 60 * (hour * 60 + minute);
        long snapshotOfDay = mongoDBService.getNumberOfSnapshotByTime(snapshot_start_timestamp, null);
        if (snapshotOfDay != -1) {
            wsSendHandler.sendSnapshotOfDay(snapshotOfDay);
        }
    }

    //在静态库中identify
    public IdentifyPojo identifyInStaticGallery(MultipartFile photo) {
        IdentifyPojo identifyPojo = null;
        FindFaceParam param = new FindFaceParam();
        param.setThreshold(config.getWarningThreshold());
        param.setGalleries(new String[]{config.getStaticGallery()});
        try {
            identifyPojo = findFaceService.imageIdentify(photo.getBytes(), param);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return identifyPojo;
    }

    //处理预警的快照
    public void handleWarningSnap(IdentifyPojo identifyPojo, IdentifyFace face, TimeModel timeModel, TbCamera camera) {
        identifyPojo.getResults().forEach((k, matchFaces) -> {
            if (!CollectionUtils.isEmpty(matchFaces)) {
                logger.info("预警警告！ Time：" + timeModel.getCatchTime());
                MatchFace matchFace = matchFaces.get(0);
                FrWarning warning = new FrWarning(face.getThumbnail(), matchFace.getFace().getThumbnail(),face.getPhoto());
                warning.setConfidence(matchFace.getConfidence());
                warning.setCatchTime(timeModel.getCatchTime());
                insertCameraData(warning, camera);
                insertPersonData(warning, matchFace);
                //本周报警次数
                long warningOfWeek = getWarningTimes(timeModel);
                warning.setWarningOfWeek(new Long(warningOfWeek).intValue());
                wsSendHandler.sendWarning(warning);
                logger.info("预警记录存入mongo");
                Map<String, Object> warningMap = new HashMap<>();
                warningMap.put("timestamp", timeModel.getTimestamp());
                warningMap.put("faceSdkId", matchFace.getFace().getId());
                String age = face.getAge().toString().split("\\.")[0];
                warningMap.put("age",age);
                warningMap.put("emotions",face.getEmotions());
                mongoDBService.addWarningFace(JSONObject.toJSONString(warning), warningMap);
            }
        });
    }

    //获取本周预警快照次数
    public long getWarningTimes(TimeModel timeModel) {
        int dayOfWeek = timeModel.getDayOfWeek();
        int hour = timeModel.getHour();
        int minute = timeModel.getMinute();
        long warning_start_timestamp = System.currentTimeMillis() - 1000 * 60 * ((dayOfWeek - 1) * 24 * 60 + hour * 60 + minute);
        long warningOfWeek = mongoDBService.getNumberOfWarningByTime(warning_start_timestamp, null);
        return warningOfWeek;
    }

    public void insertCameraData(FrWarning warning, TbCamera camera) {
        if (camera == null)
            return;
        warning.setCameraId(camera.getId());
        warning.setCameraGroupId(camera.getGroupId());
        warning.setCameraSdkId(camera.getSdkId());
        warning.setCameraName(camera.getName());
        warning.setCameraGroupName(camera.getGroupName());
    }

    public void insertPersonData(FrWarning warning, MatchFace face) {
        warning.setAge(face.getFace().getAge().intValue());
        warning.setEmotions(face.getFace().getEmotions());
        TbPerson person = personService.getPersonBySdkId(face.getFace().getId()).getData();
        if (person != null) {
            warning.setPersonName(person.getName());
            warning.setGender(person.getGender());
            warning.setPersonGroupId(person.getGroupId());
            warning.setPersonGroupName(person.getGroupName());
            warning.setIdNumber(person.getIdNumber());
        }
    }

    //在动态库中搜索快照
    public JSONObject identifySnap(IdenfitySnapParam idenfitySnapParam) {
        JSONObject result = new JSONObject();
        FindFaceParam findFaceParam = getFindFaceParam(idenfitySnapParam);
        IdentifyPojo identifyPojo = identify(idenfitySnapParam, findFaceParam);
        Map<String, String> sdkMap = getSdkIdConfidenceMap(identifyPojo);
        if (sdkMap.size() > 0) {
            result = mongoDBService.identifySnap(sdkMap, idenfitySnapParam);
            return result;
        }
        return null;
    }

    public FindFaceParam getFindFaceParam(IdenfitySnapParam param){
        FindFaceParam findFaceParam = new FindFaceParam();
        //confidence
        String confidence =param.getConfidence();
        if(!StringUtils.isEmpty(confidence)){
            double confidenceValue = Double.parseDouble(confidence);
            if(confidenceValue > 1){
                confidenceValue = confidenceValue/100;
            }
            findFaceParam.setThreshold(confidenceValue+"");
        }
        //photoUrl
        String photoUrl = param.getPhotoUrl();
        if(!StringUtils.isEmpty(photoUrl)){
            findFaceParam.setPhotoUrl(photoUrl);
        }
        findFaceParam.setN(config.getSnapIdentifyNumber());
        findFaceParam.setGalleries(new String[]{config.getSnapGallery()});
        return findFaceParam;
    }

    public IdentifyPojo identify(IdenfitySnapParam idenfitySnapParam, FindFaceParam findFaceParam){
        IdentifyPojo identifyPojo = null;
        MultipartFile photo = idenfitySnapParam.getPhoto();
        String photoUrl = idenfitySnapParam.getPhotoUrl();
        if(photo != null){
            try {
                identifyPojo = findFaceService.imageIdentify(photo.getBytes(),findFaceParam);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }else if(!StringUtils.isEmpty(photoUrl)){
            identifyPojo = findFaceService.imageIdentify(null,findFaceParam);
        }else {
            logger.info("identifySnap没有传入photo文件或photoUrl");
        }
        return identifyPojo;
    }

    public Map<String, String> getSdkIdConfidenceMap(IdentifyPojo identifyPojo){
        Map<String, String> sdkMap = new HashMap<>();
        identifyPojo.getResults().keySet().forEach((key) -> {
            List<MatchFace> matchFaces = identifyPojo.getResults().get(key);
            if (matchFaces.size() > 0) {
                for (MatchFace face : matchFaces) {
                    sdkMap.put(face.getFace().getId(), face.getConfidence() + "");
                }
            }
        });
        return sdkMap;
    }
}
