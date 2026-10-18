import { HttpErrorResponse } from '@angular/common/http';

/**
 * Message lisible à partir d'une erreur HTTP. Le backend renvoie {"message": "..."}
 * pour les erreurs métier (400 donnée invalide, 409 action impossible).
 */
export function apiError(err: HttpErrorResponse | any): string {
  if (err?.error?.message) {
    return err.error.message;
  }
  if (err?.status === 0) {
    return 'Serveur injoignable.';
  }
  return err?.message || 'Erreur inattendue.';
}
