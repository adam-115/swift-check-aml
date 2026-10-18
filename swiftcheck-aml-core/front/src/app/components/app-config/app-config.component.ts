import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';

import { ApplicationConfigService } from '../../services/application-config.service';
import { apiError } from '../../services/api-error';

/** Un champ de l'écran de configuration : nom, libellé, aide et exemple. */
interface ConfigField {
  name: string;
  label: string;
  help: string;
  placeholder: string;
  required: boolean;
}

/**
 * Écran « Configuration » (administrateurs) : connexion à Yente et dossiers utilisés par l'ingestion.
 * Les valeurs sont relues à chaque exécution de parser : aucune modification ne demande de redémarrage.
 */
@Component({
  selector: 'app-config',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './app-config.component.html',
})
export class AppConfigComponent implements OnInit {
  readonly yenteFields: ConfigField[] = [
    { name: 'yenteUrl', label: 'URL de l\'API Yente', help: 'Adresse utilisée pour le criblage et pour demander le rechargement des listes.', placeholder: 'http://localhost:8000', required: true },
    { name: 'yenteUpdateToken', label: 'Jeton de rechargement', help: 'Valeur de YENTE_UPDATE_TOKEN dans le docker-compose de Yente.', placeholder: 'adam', required: false },
    { name: 'yenteDataFolder', label: 'Dossier de données de Yente (sur ce serveur)', help: 'Les listes publiées y sont écrites : <dossier>/<dataset>/<dataset>.ndjson.', placeholder: 'D:/projects/swiftcheck-aml/yente/data', required: true },
    { name: 'yenteContainerDataPath', label: 'Même dossier vu depuis le conteneur Yente', help: 'Utilisé pour écrire les chemins dans le manifest (volume ./data:/data).', placeholder: '/data', required: true },
    { name: 'yenteManifestPath', label: 'Fichier manifest.yml', help: 'Liste des datasets indexés par Yente ; mis à jour à chaque publication.', placeholder: 'D:/projects/swiftcheck-aml/yente/manifest.yml', required: true },
  ];

  readonly folderFields: ConfigField[] = [
    { name: 'archiveFolder', label: 'Dossier d\'archive des sources', help: 'Copie signée (SHA-256) de chaque fichier source utilisé : preuve des données.', placeholder: 'D:/swiftcheck/archive', required: true },
    { name: 'workFolder', label: 'Dossier de travail', help: 'Fichiers temporaires d\'une exécution, supprimés à la fin.', placeholder: 'D:/swiftcheck/work', required: true },
  ];

  /** Les deux cartes de l'écran. */
  readonly groups = [
    { title: 'Yente', fields: this.yenteFields },
    { title: 'Dossiers de l\'ingestion', fields: this.folderFields },
  ];

  form: FormGroup;
  isSaving = false;
  successMessage = '';
  errorMessage = '';

  constructor(private fb: FormBuilder, private configService: ApplicationConfigService) {
    // Un contrôle par champ ; obligatoire selon la définition ci-dessus.
    const controls: Record<string, unknown[]> = {};
    for (const field of [...this.yenteFields, ...this.folderFields]) {
      controls[field.name] = ['', field.required ? Validators.required : []];
    }
    this.form = this.fb.group(controls);
  }

  ngOnInit(): void {
    this.configService.get().subscribe({
      next: config => this.form.patchValue(config),
      error: err => (this.errorMessage = apiError(err)),
    });
  }

  invalid(name: string): boolean {
    const control = this.form.get(name);
    return !!control && control.invalid && control.touched;
  }

  save(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      return;
    }
    this.isSaving = true;
    this.successMessage = '';
    this.errorMessage = '';
    this.configService.update(this.form.value).subscribe({
      next: config => {
        this.form.patchValue(config);
        this.isSaving = false;
        this.successMessage = 'Configuration enregistrée. Elle sera utilisée dès la prochaine exécution.';
      },
      error: err => {
        this.isSaving = false;
        this.errorMessage = apiError(err);
      },
    });
  }
}
