package com.swiftcheck.aml.controllers;

import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Transforme les exceptions des services en réponses HTTP lisibles, au format {@code {"message": "..."}}.
 * Les services lèvent des exceptions Java simples ; ce n'est qu'ici qu'on choisit le code HTTP.
 * <ul>
 *   <li>{@link IllegalArgumentException} : donnée invalide ou introuvable → 400 ;</li>
 *   <li>{@link IllegalStateException} : action impossible maintenant (exécution déjà en cours…) → 409 ;</li>
 *   <li>{@link DataIntegrityViolationException} : contrainte de base de données (ex. fournisseur encore utilisé) → 409.</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> integrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Opération refusée : cet élément est encore utilisé ou existe déjà."));
    }
}
