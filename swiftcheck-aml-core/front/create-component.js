const fs = require('fs');
const path = require('path');

const frontDir = "D:\\swiftcheck-aml\\swiftcheck-aml\\swiftcheck-aml-core\\front";
const componentsDir = path.join(frontDir, 'src/app/components/app-config');
if (!fs.existsSync(componentsDir)) fs.mkdirSync(componentsDir, { recursive: true });

// app-config.component.ts
const tsContent = `import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApplicationConfigService } from '../../services/application-config.service';
import { ApplicationConfigDTO } from '../../models/appTypes';

@Component({
  selector: 'app-config',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './app-config.component.html'
})
export class AppConfigComponent implements OnInit {
  configForm: FormGroup;
  isSaving = false;
  successMessage = '';
  errorMessage = '';
  currentConfigId?: number;

  constructor(
    private fb: FormBuilder,
    private configService: ApplicationConfigService
  ) {
    this.configForm = this.fb.group({
      yentUrl: ['', Validators.required],
      yentInputDataFolder: ['', Validators.required]
    });
  }

  ngOnInit(): void {
    this.loadConfig();
  }

  loadConfig(): void {
    this.configService.getAll(0, 1).subscribe({
      next: (page) => {
        if (page.content && page.content.length > 0) {
          const config = page.content[0];
          this.currentConfigId = config.id;
          this.configForm.patchValue({
            yentUrl: config.yentUrl,
            yentInputDataFolder: config.yentInputDataFolder
          });
        }
      },
      error: (err) => console.error('Erreur lors du chargement de la configuration', err)
    });
  }

  onSubmit(): void {
    if (this.configForm.valid) {
      this.isSaving = true;
      this.successMessage = '';
      this.errorMessage = '';
      
      const configData: ApplicationConfigDTO = {
        id: this.currentConfigId,
        yentUrl: this.configForm.value.yentUrl,
        yentInputDataFolder: this.configForm.value.yentInputDataFolder
      };

      this.configService.save(configData).subscribe({
        next: (savedConfig) => {
          this.currentConfigId = savedConfig.id;
          this.isSaving = false;
          this.successMessage = 'Configuration enregistrée avec succès !';
          setTimeout(() => this.successMessage = '', 3000);
        },
        error: (err) => {
          this.isSaving = false;
          this.errorMessage = 'Une erreur est survenue lors de la sauvegarde.';
          console.error(err);
        }
      });
    }
  }
}
`;
fs.writeFileSync(path.join(componentsDir, 'app-config.component.ts'), tsContent);

// app-config.component.html
const htmlContent = `<div class="min-h-screen bg-gray-50 flex flex-col pt-16 items-center font-sans">
  
  <!-- Logo Section -->
  <div class="mb-8 flex items-center gap-4">
    <img src="assets/images/logo.png" alt="Swift Check Logo" class="w-16 h-16 object-contain">
    <div>
      <h1 class="text-3xl font-bold text-slate-900 tracking-tight">Swift Check</h1>
      <p class="text-gray-500 font-medium text-sm">AML Verification Platform</p>
    </div>
  </div>

  <!-- Form Card -->
  <div class="bg-white rounded-xl shadow-lg border border-gray-100 overflow-hidden w-full max-w-2xl">
    <!-- Card Header -->
    <div class="bg-slate-900 px-6 py-5 flex items-center gap-3">
      <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
      </svg>
      <h2 class="text-xl font-semibold text-white">Configuration du Système</h2>
    </div>

    <!-- Form Content -->
    <form [formGroup]="configForm" (ngSubmit)="onSubmit()" class="p-8 space-y-6">
      
      <!-- Messages -->
      <div *ngIf="successMessage" class="bg-emerald-50 border border-emerald-100 text-emerald-700 p-4 rounded-lg flex items-center gap-3">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-emerald-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
        <span class="font-medium">{{ successMessage }}</span>
      </div>
      
      <div *ngIf="errorMessage" class="bg-red-50 border border-red-100 text-red-700 p-4 rounded-lg flex items-center gap-3">
        <span class="font-medium">{{ errorMessage }}</span>
      </div>

      <!-- Fields -->
      <div class="space-y-2">
        <label for="yentUrl" class="block text-sm font-semibold text-slate-800">URL du Moteur Yente</label>
        <input type="text" id="yentUrl" formControlName="yentUrl" placeholder="ex: http://localhost:8000"
               class="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-slate-900 focus:ring-2 focus:ring-slate-900 focus:border-slate-900 outline-none transition-all shadow-sm">
        <p *ngIf="configForm.get('yentUrl')?.invalid && configForm.get('yentUrl')?.touched" class="text-red-500 text-sm mt-1">L'URL est requise.</p>
      </div>

      <div class="space-y-2">
        <label for="yentInputDataFolder" class="block text-sm font-semibold text-slate-800">Dossier des Données Entrantes</label>
        <input type="text" id="yentInputDataFolder" formControlName="yentInputDataFolder" placeholder="ex: /data/yente"
               class="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-slate-900 focus:ring-2 focus:ring-slate-900 focus:border-slate-900 outline-none transition-all shadow-sm">
        <p *ngIf="configForm.get('yentInputDataFolder')?.invalid && configForm.get('yentInputDataFolder')?.touched" class="text-red-500 text-sm mt-1">Le dossier est requis.</p>
      </div>

      <!-- Actions -->
      <div class="pt-6 border-t border-gray-100 flex justify-end">
        <button type="submit" [disabled]="configForm.invalid || isSaving"
                class="bg-emerald-600 hover:bg-emerald-700 disabled:opacity-60 disabled:cursor-not-allowed text-white font-semibold py-2.5 px-6 rounded-lg transition-all shadow-md hover:shadow-lg flex items-center gap-2">
          <svg *ngIf="!isSaving" xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
          </svg>
          <svg *ngIf="isSaving" class="animate-spin h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
          </svg>
          {{ isSaving ? 'Enregistrement en cours...' : 'Sauvegarder' }}
        </button>
      </div>

    </form>
  </div>
</div>
`;
fs.writeFileSync(path.join(componentsDir, 'app-config.component.html'), htmlContent);

// app.component.ts - Make sure it imports AppConfigComponent
const appCompPath = path.join(frontDir, 'src/app/app.component.ts');
let appComp = fs.readFileSync(appCompPath, 'utf8');
if (!appComp.includes('AppConfigComponent')) {
    appComp = appComp.replace("import { RouterOutlet } from '@angular/router';", "import { RouterOutlet } from '@angular/router';\nimport { AppConfigComponent } from './components/app-config/app-config.component';");
    appComp = appComp.replace("imports: [RouterOutlet]", "imports: [RouterOutlet, AppConfigComponent]");
    fs.writeFileSync(appCompPath, appComp);
}

// app.component.html
const appHtmlPath = path.join(frontDir, 'src/app/app.component.html');
fs.writeFileSync(appHtmlPath, '<app-config></app-config>\n'); // Only component for now

console.log('AppConfigComponent created successfully.');
