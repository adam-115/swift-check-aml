import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AmlAdvancedVerificationComponent } from './aml-advanced-verification.component';

describe('AmlAdvancedVerificationComponent', () => {
  let component: AmlAdvancedVerificationComponent;
  let fixture: ComponentFixture<AmlAdvancedVerificationComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AmlAdvancedVerificationComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(AmlAdvancedVerificationComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
