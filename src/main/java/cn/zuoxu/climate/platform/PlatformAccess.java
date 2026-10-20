package cn.zuoxu.climate.platform;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@Service
public class PlatformAccess {
    private final PlatformStore store;
    public PlatformAccess(PlatformStore store){this.store=store;}
    public Map<String,Object> dataset(PlatformUsers.Account user,long id,boolean write) {
        Map<String,Object> row=store.one("SELECT d.*,m.permission member_permission FROM platform_dataset d LEFT JOIN platform_member m ON m.dataset_id=d.id AND m.user_id=? WHERE d.id=?",user.id(),id);
        if(row==null)throw missing();
        boolean example=PlatformStore.flag(row,"is_example"),owner=PlatformStore.number(row,"owner_id")==user.id();
        boolean readable=user.admin()||owner||example||row.get("member_permission")!=null;
        if(!readable)throw missing(); // Do not reveal another user's dataset existence.
        boolean writable=!example&&user.canWrite()&&(user.admin()||owner||"WRITE".equals(row.get("member_permission")));
        if(write&&!writable)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Dataset is read-only for this account");
        row.put("can_write",writable);row.put("can_manage",!example&&(user.admin()||owner));return row;
    }
    public static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Resource not found or inaccessible");}
}
