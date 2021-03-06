package io.github.pivopil.rest.services;

import io.github.pivopil.rest.constants.ROLES;
import io.github.pivopil.rest.services.security.CustomACLService;
import io.github.pivopil.rest.services.security.CustomSecurityService;
import io.github.pivopil.share.builders.Builders;
import io.github.pivopil.share.builders.REGEX;
import io.github.pivopil.share.builders.impl.UserBuilder;
import io.github.pivopil.share.entities.impl.Role;
import io.github.pivopil.share.entities.impl.User;
import io.github.pivopil.share.exceptions.ExceptionAdapter;
import io.github.pivopil.share.persistence.RoleRepository;
import io.github.pivopil.share.persistence.UserRepository;
import io.github.pivopil.share.viewmodels.impl.UserViewModel;
import net.sf.oval.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.repository.query.Param;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@DependsOn("ovalValidator")
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    private final RoleRepository roleRepository;

    private final CustomSecurityService customSecurityService;

    private final CustomACLService customACLService;

    private final PasswordEncoder passwordEncoder;

    private final Validator ovalValidator;

    @Autowired
    public CustomUserDetailsService(UserRepository userRepository, CustomSecurityService customSecurityService, RoleRepository roleRepository, CustomACLService customACLService, PasswordEncoder passwordEncoder, Validator ovalValidator) {
        this.userRepository = userRepository;
        this.customSecurityService = customSecurityService;
        this.roleRepository = roleRepository;
        this.customACLService = customACLService;
        this.passwordEncoder = passwordEncoder;
        this.ovalValidator = ovalValidator;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByLogin(username);
        if (user == null) {
            throw new UsernameNotFoundException(String.format("User %s does not exist!", username));
        }
        return new UserRepositoryUserDetails(user);
    }

    @PreAuthorize("isAuthenticated()")
    @PostFilter("hasPermission(filterObject, 'READ')")
    public Iterable<User> findAll() {
        return userRepository.findAll();
    }


    public UserViewModel getSingle(Long id) {
        UserViewModel user = findUserById(id);
        if (user == null) {
            throw new UsernameNotFoundException(String.format("User with id = %s does not exist!", id));
        }
        return user;
    }

    @PreAuthorize("isAuthenticated() && #id != null")
    @PostAuthorize("returnObject == null || hasPermission(returnObject, 'READ')")
    private UserViewModel findUserById(Long id) {
        User one = userRepository.findOne(id);
        UserBuilder userBuilder = Builders.of(one);
        UserViewModel userViewModel = userBuilder.buildViewModel();
        String ownerOfObject = customSecurityService.getOwnerOfObject(one);
        List<String> acls = customSecurityService.getMyAclForObject(one);
        userViewModel.setOwner(ownerOfObject);
        userViewModel.setAcls(acls);
        return userViewModel;
    }

    @Transactional
    public UserViewModel createNewUser(User newUser) {

        User currentUser = newUser;

        String password = currentUser.getPassword();

        validateUserPassword(password);

        Set<Role> roles = new HashSet<>();

        for (Role role : currentUser.getRoles()) {
            String name = role.getName();
            Role oneByName = roleRepository.findOneByName(name);
            if (oneByName == null) {
                throw new IllegalArgumentException(String.format("Role with name %s does note exist", name));
            }
            roles.add(oneByName);
        }

        currentUser.setRoles(roles);

        if (roles.size() == 0) {
            throw new BadCredentialsException("New User object should have at least one valid role!");
        }

        //case 1 admin can create local_admin and local_user
        Boolean isCreatorAdmin = customSecurityService.isUserHasRole(ROLES.ROLE_ADMIN);

        Boolean hasAdminRole = customSecurityService.isRolesContainRoleName(roles, ROLES.ROLE_ADMIN);

        if (hasAdminRole) {
            throw new BadCredentialsException("User can not create new user with ROLE_ADMIN!");
        }

        if (!isCreatorAdmin) {
            // if user does not have ROLE_ADMIN check if he is LOCAL_ADMIN
            List<Role> localAdminSet = customSecurityService.getRolesNameContains(ROLES.LOCAL_ADMIN);

            if (localAdminSet.size() == 0) {
                throw new BadCredentialsException("User with LOCAL_ADMIN role can not create new user with LOCAL_ADMIN role!");
            }

            String authenticatedUserRoleName = localAdminSet.get(0).getName();

            Boolean isLocalUser = customSecurityService.isRolesContainRoleName(roles, authenticatedUserRoleName.replace(ROLES.LOCAL_ADMIN, ROLES.LOCAL_USER));

            if (isLocalUser) {
                throw new BadCredentialsException("User with LOCAL_ADMIN role can create user only for the same org");
            }
        }

        UserBuilder userBuilder = Builders.of(currentUser);
        String encodedPassword = passwordEncoder.encode(password);
        currentUser = userBuilder
                .password(encodedPassword)
                .enabled(Boolean.TRUE)
                .withOvalValidator(ovalValidator)
                .build();

        currentUser = add(currentUser);

        userBuilder = Builders.of(currentUser);
        UserViewModel userViewModel = userBuilder.buildViewModel();

        String ownerOfObject = customSecurityService.getOwnerOfObject(currentUser);
        List<String> acls = customSecurityService.getMyAclForObject(currentUser);

        userViewModel.setOwner(ownerOfObject);
        userViewModel.setAcls(acls);

        return userViewModel;
    }

    @PreAuthorize("isAuthenticated() && #newUser != null")
    private User add(@Param("newUser") User newUser) {
        User currentUser = userRepository.save(newUser);
        customSecurityService.addAclPermissions(currentUser);
        // let new user edit himself
        customACLService.persistReadWritePermissionsForDomainObject(currentUser, currentUser.getLogin(), true);
        return currentUser;
    }

    @PreAuthorize("isAuthenticated()")
    public UserDetails me() {
        String userLogin = customSecurityService.userLoginFromAuthentication();
        return loadUserByUsername(userLogin);
    }

    @PreAuthorize("isAuthenticated() && hasPermission(#user, 'WRITE') && #user != null")
    public User edit(@Param("user") User user) {

        User currentUser = user;

        Long userId = currentUser.getId();

        User userFromDB = userRepository.findOne(userId);

        if (userFromDB == null) {
            throw new ExceptionAdapter(new BadCredentialsException("there is no user with id = " + userId));
        }

        String password = currentUser.getPassword();

        validateUserPassword(password);

        UserBuilder userBuilder = Builders.of(currentUser);

        String encodedPassword = passwordEncoder.encode(password);

        currentUser = userBuilder.password(encodedPassword).withOvalValidator(ovalValidator).build();

        currentUser = userRepository.save(currentUser);

        return currentUser;
    }

    private void validateUserPassword(String password) {
        if (password == null || !password.matches(REGEX.PASSWORD)) {
            throw new ExceptionAdapter(new BadCredentialsException("Password should have at least 8 characters length, 2 letters in Upper Case, 1 Special Character (!@#$&*), 2 numerals (0-9), 3 letters in Lower Case"));
        }
    }

    // todo: find way to remove user object in case if user is owner of another objects
    @PreAuthorize("isAuthenticated() && hasPermission(#user, 'WRITE') && #user != null")
    private void delete(@Param("user") User user) {

        User currentUser = user;

        Boolean hasAdminRole = customSecurityService.isRolesContainRoleName(currentUser.getRoles(), ROLES.ROLE_ADMIN);

        if (hasAdminRole) {
            throw new ExceptionAdapter(new BadCredentialsException("Admin can not disable himself!"));
        }

        userRepository.delete(currentUser);
        customSecurityService.removeAclPermissions(currentUser);
        customACLService.deleteReadWritePermissionsFromDatabase(currentUser, currentUser.getLogin(), true);
    }

    @Transactional
    public void deleteById(Long id) {
        User one = userRepository.findOne(id);
        delete(one);
    }

    @PreAuthorize("isAuthenticated() && hasPermission(#user, 'WRITE') && #user != null")
    private void disableUser(User user) {

        User currentUser = user;

        Boolean hasAdminRole = customSecurityService.isRolesContainRoleName(currentUser.getRoles(), ROLES.ROLE_ADMIN);

        if (hasAdminRole) {
            throw new BadCredentialsException("Admin can not disable himself!");
        }

        currentUser.setEnabled(Boolean.FALSE);
        userRepository.save(currentUser);
    }

    @Transactional
    public void disableById(Long id) {
        User one = userRepository.findOne(id);
        disableUser(one);
    }

    private final static class UserRepositoryUserDetails extends User implements UserDetails {

        private static final long serialVersionUID = 1L;

        private UserRepositoryUserDetails(User user) {
            super(user);
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return getRoles();
        }

        @Override
        public String getUsername() {
            return getLogin();
        }

        @Override
        public boolean isAccountNonExpired() {
            return true;
        }

        @Override
        public boolean isAccountNonLocked() {
            return true;
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return getEnabled();
        }

    }

}
