import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, FormArray, Validators, ReactiveFormsModule } from '@angular/forms';
import { AmlVerificationService } from '../../services/aml-verification.service';
import { AmlAdvancedVerificationRequestDTO, AmlAdvancedVerificationResponseDTO } from '../../models/appTypes';
import { RouterModule } from '@angular/router';

@Component({
  selector: 'app-aml-advanced-verification',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterModule],
  templateUrl: './aml-advanced-verification.component.html',
  styleUrl: './aml-advanced-verification.component.css'
})
export class AmlAdvancedVerificationComponent implements OnInit {
  advancedForm!: FormGroup;
  isLoading = false;
  results: AmlAdvancedVerificationResponseDTO | null = null;
  error: string | null = null;
  objectKeys = Object.keys;

  constructor(
    private fb: FormBuilder,
    private verificationService: AmlVerificationService
  ) {}

  ngOnInit(): void {
    this.advancedForm = this.fb.group({
      companyName: ['', Validators.required],
      dataset: ['default', Validators.required],
      ubos: this.fb.array([])
    });
    // Add first UBO field by default
    this.addUbo();
  }

  get ubos(): FormArray {
    return this.advancedForm.get('ubos') as FormArray;
  }

  addUbo(): void {
    this.ubos.push(this.fb.control('', Validators.required));
  }

  removeUbo(index: number): void {
    this.ubos.removeAt(index);
  }

  onSubmit(): void {
    if (this.advancedForm.invalid) {
      this.advancedForm.markAllAsTouched();
      return;
    }

    this.isLoading = true;
    this.error = null;
    this.results = null;

    const formValue = this.advancedForm.value;
    const request: AmlAdvancedVerificationRequestDTO = {
      companyName: formValue.companyName,
      dataset: formValue.dataset,
      ubos: formValue.ubos.filter((ubo: string) => ubo && ubo.trim() !== ''),
      schema: 'Company'
    };

    this.verificationService.verifyAdvanced(request).subscribe({
      next: (res) => {
        this.results = res;
        this.isLoading = false;
      },
      error: (err) => {
        this.error = err.message || 'Une erreur est survenue lors de la vérification';
        this.isLoading = false;
      }
    });
  }

  getUboKeys(): string[] {
    if (!this.results || !this.results.uboResults) return [];
    return Object.keys(this.results.uboResults);
  }
}
