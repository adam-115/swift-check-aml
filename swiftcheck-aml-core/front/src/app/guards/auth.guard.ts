import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { KeycloakService } from '../services/keycloak.service';

export const authGuard: CanActivateFn = (route, state) => {
  const keycloakService = inject(KeycloakService);
  const router = inject(Router);

  if (!keycloakService.isLoggedIn()) {
    keycloakService.login();
    return false;
  }

  const requiredRoles = route.data?.['roles'] as string[] | undefined;
  if (!requiredRoles || requiredRoles.length === 0) {
    return true;
  }

  const hasAccess = requiredRoles.some(role => keycloakService.hasRole(role));
  if (!hasAccess) {
    console.warn(`Accès refusé pour ${keycloakService.getUsername()} : rôles requis [${requiredRoles.join(', ')}]`);
    router.navigate(['/wiki']);
    return false;
  }

  return true;
};
