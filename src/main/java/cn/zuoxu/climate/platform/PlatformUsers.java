package cn.zuoxu.climate.platform;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class PlatformUsers implements UserDetailsService {
    public record Account(long id,String username,String displayName,String role,boolean enabled) {
        public boolean admin(){return role.equals("ADMIN");}
        public boolean canWrite(){return !role.equals("VIEWER");}
    }
    private final PlatformStore store;
    private final PasswordEncoder encoder;
    public PlatformUsers(PlatformStore store,PasswordEncoder encoder){this.store=store;this.encoder=encoder;}
    @Override public UserDetails loadUserByUsername(String username) {
        Map<String,Object> row=store.one("SELECT * FROM platform_user WHERE username=?",username);
        if(row==null)throw new UsernameNotFoundException("Invalid credentials");
        Timestamp lock=(Timestamp)row.get("locked_until");
        return User.withUsername(username).password((String)row.get("password_hash")).roles((String)row.get("role"))
            .disabled(!PlatformStore.flag(row,"enabled")).accountLocked(lock!=null&&lock.toInstant().isAfter(Instant.now())).build();
    }
    public Account current() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||auth.getName().equals("anonymousUser"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Login required");
        Map<String,Object> row=store.one("SELECT id,username,display_name,role,enabled FROM platform_user WHERE username=?",auth.getName());
        if(row==null||!PlatformStore.flag(row,"enabled"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account disabled or unavailable");
        return account(row);
    }
    public Account requireAdmin(){Account user=current();if(!user.admin())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator required");return user;}
    public List<Map<String,Object>> list(){requireAdmin();return store.jdbc.queryForList("SELECT id,username,display_name,role,enabled,created_at FROM platform_user ORDER BY id LIMIT 200");}
    @Transactional public Account create(String username,String display,String password,String role) {
        Account actor=requireAdmin();long id=createInternal(username,display,password,role);store.audit(actor.id(),null,"CREATE_USER",Long.toString(id));
        return account(store.one("SELECT * FROM platform_user WHERE id=?",id));
    }
    long createInternal(String username,String display,String password,String role) {
        if(username==null||!username.matches("[a-z][a-z0-9_.-]{2,39}"))throw new IllegalArgumentException("Username: 3-40 lowercase ASCII letters, digits, dot, underscore or hyphen");
        if(display==null||display.isBlank()||display.length()>80)throw new IllegalArgumentException("Display name is required, max 80 characters");
        validatePassword(password);
        if(role==null||!Set.of("ADMIN","RESEARCHER","VIEWER").contains(role))throw new IllegalArgumentException("Invalid user role");
        try{return store.insert("INSERT INTO platform_user(username,display_name,password_hash,role) VALUES(?,?,?,?)",username,display.strip(),encoder.encode(password),role);}
        catch(DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Username already exists");}
    }
    @Transactional public void enable(long id,boolean enabled) {
        Account actor=requireAdmin();if(id==actor.id())throw new IllegalArgumentException("Cannot disable your own account");
        if(store.jdbc.update("UPDATE platform_user SET enabled=? WHERE id=?",enabled,id)!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");
        store.audit(actor.id(),null,enabled?"ENABLE_USER":"DISABLE_USER",Long.toString(id));
    }
    @Transactional public void changePassword(String oldPassword,String password) {
        Account actor=current();Map<String,Object> row=store.one("SELECT password_hash FROM platform_user WHERE id=?",actor.id());
        if(oldPassword==null||!encoder.matches(oldPassword,(String)row.get("password_hash")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Current password is incorrect");
        validatePassword(password);store.jdbc.update("UPDATE platform_user SET password_hash=? WHERE id=?",encoder.encode(password),actor.id());
        store.audit(actor.id(),null,"CHANGE_PASSWORD",Long.toString(actor.id()));
    }
    static void validatePassword(String password) {
        if(password==null||password.length()<12||password.getBytes(StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("Password must be at least 12 characters and at most 72 UTF-8 bytes");
    }
    public void loginFailed(String username){store.jdbc.update("UPDATE platform_user SET failed_logins=failed_logins+1,locked_until=IF(failed_logins>=5,DATE_ADD(NOW(6),INTERVAL 5 MINUTE),locked_until) WHERE username=?",username);}
    public void loginSucceeded(String username){store.jdbc.update("UPDATE platform_user SET failed_logins=0,locked_until=NULL WHERE username=?",username);}
    private static Account account(Map<String,Object> row){return new Account(PlatformStore.number(row,"id"),(String)row.get("username"),(String)row.get("display_name"),(String)row.get("role"),PlatformStore.flag(row,"enabled"));}
}
