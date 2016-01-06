package com.ctrip.zeus.service.model.handler.impl;

import com.ctrip.zeus.dal.core.*;
import com.ctrip.zeus.exceptions.ValidationException;
import com.ctrip.zeus.model.entity.Group;
import com.ctrip.zeus.model.transform.DefaultSaxParser;
import com.ctrip.zeus.service.model.handler.GroupSync;
import com.ctrip.zeus.service.model.handler.MultiRelMaintainer;
import com.ctrip.zeus.support.C;
import org.springframework.stereotype.Component;
import org.unidal.dal.jdbc.DalException;

import javax.annotation.Resource;
import java.util.*;

/**
 * Created by zhoumy on 2015/9/23.
 */
@Component("groupEntityManager")
public class GroupEntityManager implements GroupSync {
    @Resource
    private GroupDao groupDao;
    @Resource
    private ArchiveGroupDao archiveGroupDao;
    @Resource
    private RGroupVgDao rGroupVgDao;
    @Resource
    private RGroupStatusDao rGroupStatusDao;
    @Resource
    private MultiRelMaintainer groupGsRelMaintainer;
    @Resource
    private MultiRelMaintainer groupVsRelMaintainer;
    @Resource
    private ConfGroupActiveDao confGroupActiveDao;

    @Override
    public void add(Group group) throws Exception {
        group.setVersion(1);
        GroupDo d = C.toGroupDo(0L, group);
        // if app id is null, it must be virtual group
        if (d.getAppId() == null) d.setAppId("VirtualGroup");
        groupDao.insert(d);
        group.setId(d.getId());
        archiveGroupDao.insert(new ArchiveGroupDo().setGroupId(group.getId()).setVersion(group.getVersion())
                .setContent(ContentWriters.writeGroupContent(group)));
        rGroupStatusDao.insertOrUpdate(new RelGroupStatusDo().setGroupId(group.getId()).setOfflineVersion(group.getVersion()));
        groupVsRelMaintainer.relAdd(group, RelGroupVsDo.class, group.getGroupVirtualServers());
        groupGsRelMaintainer.relAdd(group, RelGroupGsDo.class, group.getGroupServers());
    }

    @Override
    public void add(Group group, boolean isVirtual) throws Exception {
        add(group);
        if (isVirtual) relSyncVg(group);
    }

    @Override
    public void update(Group group) throws Exception {
        GroupDo check = groupDao.findById(group.getId(), GroupEntity.READSET_FULL);
        if (check.getVersion() > group.getVersion())
            throw new ValidationException("Newer Group version is detected.");
        group.setVersion(group.getVersion() + 1);

        GroupDo d = C.toGroupDo(group.getId(), group).setAppId("VirtualGroup");
        groupDao.updateById(d, GroupEntity.UPDATESET_FULL);
        archiveGroupDao.insert(new ArchiveGroupDo().setGroupId(group.getId()).setVersion(group.getVersion())
                .setContent(ContentWriters.writeGroupContent(group)));
        rGroupStatusDao.insertOrUpdate(new RelGroupStatusDo().setGroupId(group.getId()).setOfflineVersion(group.getVersion()));
        groupVsRelMaintainer.relUpdateOffline(group, RelGroupVsDo.class, group.getGroupVirtualServers());
        groupGsRelMaintainer.relUpdateOffline(group, RelGroupGsDo.class, group.getGroupServers());
    }

    @Override
    public void updateStatus(Group[] groups) throws Exception {
        RelGroupStatusDo[] dos = new RelGroupStatusDo[groups.length];
        for (int i = 0; i < dos.length; i++) {
            dos[i] = new RelGroupStatusDo().setGroupId(groups[i].getId()).setOnlineVersion(groups[i].getVersion());
        }
        rGroupStatusDao.updateOnlineVersionByGroup(dos, RGroupStatusEntity.UPDATESET_UPDATE_ONLINE_STATUS);
        Map<Long, Group> ref = new HashMap<>();
        for (Group group : groups) {
            ref.put(group.getId(), group);
        }
        List<RelGroupStatusDo> check = rGroupStatusDao.findByGroups(ref.keySet().toArray(new Long[ref.size()]), RGroupStatusEntity.READSET_FULL);
        for (RelGroupStatusDo relGroupStatusDo : check) {
            if (relGroupStatusDo.getOnlineVersion() != relGroupStatusDo.getOfflineVersion()) {
                Group group = ref.get(relGroupStatusDo.getGroupId());
                groupVsRelMaintainer.relUpdateOnline(group, RelGroupVsDo.class, group.getGroupVirtualServers());
                groupGsRelMaintainer.relUpdateOnline(group, RelGroupGsDo.class, group.getGroupServers());
            }
        }
    }

    @Override
    public int delete(Long groupId) throws Exception {
        groupVsRelMaintainer.relDelete(groupId);
        groupGsRelMaintainer.relDelete(groupId);
        rGroupVgDao.deleteByGroup(new RelGroupVgDo().setGroupId(groupId));
        rGroupStatusDao.deleteAllByGroup(new RelGroupStatusDo().setGroupId(groupId));
        int count = groupDao.deleteById(new GroupDo().setId(groupId));
        archiveGroupDao.deleteByGroup(new ArchiveGroupDo().setGroupId(groupId));
        return count;
    }

    @Override
    public Set<Long> port(Long[] groupIds) throws Exception {
        List<Group> toUpdate = new ArrayList<>();
        Set<Long> failed = new HashSet<>();
        for (ArchiveGroupDo archiveGroupDo : archiveGroupDao.findMaxVersionByGroups(groupIds, ArchiveGroupEntity.READSET_FULL)) {
            try {
                toUpdate.add(DefaultSaxParser.parseEntity(Group.class, archiveGroupDo.getContent()));
            } catch (Exception ex) {
                failed.add(archiveGroupDo.getGroupId());
            }
        }
        RelGroupStatusDo[] dos = new RelGroupStatusDo[toUpdate.size()];
        for (int i = 0; i < dos.length; i++) {
            dos[i] = new RelGroupStatusDo().setGroupId(toUpdate.get(i).getId()).setOfflineVersion(toUpdate.get(i).getVersion());
        }
        rGroupStatusDao.insertOrUpdate(dos);
        for (Group group : toUpdate) {
            groupVsRelMaintainer.relUpdateOffline(group, RelGroupVsDo.class, group.getGroupVirtualServers());
            groupGsRelMaintainer.relUpdateOffline(group, RelGroupGsDo.class, group.getGroupServers());
        }
        groupIds = new Long[toUpdate.size()];
        for (int i = 0; i < groupIds.length; i++) {
            groupIds[i] = toUpdate.get(i).getId();
        }
        List<ConfGroupActiveDo> ref = confGroupActiveDao.findAllByGroupIds(groupIds, ConfGroupActiveEntity.READSET_FULL);
        toUpdate.clear();

        for (ConfGroupActiveDo confGroupActiveDo : ref) {
            try {
                toUpdate.add(DefaultSaxParser.parseEntity(Group.class, confGroupActiveDo.getContent()));
            } catch (Exception ex) {
                failed.add(confGroupActiveDo.getGroupId());
            }
        }
        updateStatus(toUpdate.toArray(new Group[toUpdate.size()]));
        return failed;
    }

    private void relSyncVg(Group group) throws DalException {
        rGroupVgDao.insert(new RelGroupVgDo().setGroupId(group.getId()));
    }
}