package com.swiftcheck.aml.service;

import com.swiftcheck.aml.dto.UserDTO;
import com.swiftcheck.aml.entities.UserEntity;
import com.swiftcheck.aml.entities.UserRole;
import com.swiftcheck.aml.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public UserEntity syncUserFromJwt(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        final String username = (preferredUsername != null && !preferredUsername.isBlank())
                ? preferredUsername
                : jwt.getClaimAsString("sub");
        String email = jwt.getClaimAsString("email");
        String firstName = jwt.getClaimAsString("given_name");
        String lastName = jwt.getClaimAsString("family_name");

        // Extraction des rôles depuis realm_access.roles
        Set<UserRole> roles = extractRoles(jwt);

        UserEntity user = userRepository.findByKeycloakId(keycloakId)
                .or(() -> userRepository.findByUsername(username))
                .orElseGet(() -> UserEntity.builder()
                        .keycloakId(keycloakId)
                        .username(username)
                        .createdDate(new Date())
                        .build());

        user.setKeycloakId(keycloakId);
        user.setUsername(username);
        if (email != null) user.setEmail(email);
        if (firstName != null) user.setFirstName(firstName);
        if (lastName != null) user.setLastName(lastName);
        user.setRoles(roles);
        user.setLastLoginDate(new Date());

        return userRepository.save(user);
    }

    private Set<UserRole> extractRoles(Jwt jwt) {
        Set<UserRole> roles = new HashSet<>();

        // 1. Extraction depuis realm_access.roles
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.containsKey("roles")) {
            Collection<?> realmRoles = (Collection<?>) realmAccess.get("roles");
            for (Object r : realmRoles) {
                if (r != null) {
                    addRoleIfMatches(roles, r.toString());
                }
            }
        }

        // 2. Extraction depuis resource_access (rôles client)
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null) {
            resourceAccess.values().forEach(client -> {
                if (client instanceof Map<?, ?> clientMap && clientMap.containsKey("roles")) {
                    Collection<?> clientRoles = (Collection<?>) clientMap.get("roles");
                    for (Object r : clientRoles) {
                        if (r != null) {
                            addRoleIfMatches(roles, r.toString());
                        }
                    }
                }
            });
        }

        // Par défaut, si aucun rôle explicite n'est trouvé, assigner TESTER
        if (roles.isEmpty()) {
            roles.add(UserRole.TESTER);
        }
        return roles;
    }

    private void addRoleIfMatches(Set<UserRole> roles, String roleName) {
        String roleStr = roleName.toUpperCase();
        if ("ADMIN".equals(roleStr) || "ROLE_ADMIN".equals(roleStr)) {
            roles.add(UserRole.ADMIN);
        } else if ("TESTER".equals(roleStr) || "ROLE_TESTER".equals(roleStr)) {
            roles.add(UserRole.TESTER);
        }
    }

    public UserDTO toDTO(UserEntity entity) {
        if (entity == null) return null;
        return UserDTO.builder()
                .id(entity.getId())
                .keycloakId(entity.getKeycloakId())
                .username(entity.getUsername())
                .email(entity.getEmail())
                .firstName(entity.getFirstName())
                .lastName(entity.getLastName())
                .roles(entity.getRoles().stream().map(Enum::name).collect(Collectors.toSet()))
                .enabled(entity.isEnabled())
                .createdDate(entity.getCreatedDate())
                .lastLoginDate(entity.getLastLoginDate())
                .build();
    }

    public List<UserDTO> findAllUsers() {
        return userRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public Optional<UserDTO> findById(Long id) {
        return userRepository.findById(id).map(this::toDTO);
    }
}
