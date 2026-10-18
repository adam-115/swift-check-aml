import { TestBed } from '@angular/core/testing';

import { AmlVerificationService } from './aml-verification.service';

describe('AmlVerificationService', () => {
  let service: AmlVerificationService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(AmlVerificationService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
