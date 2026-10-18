import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AmlSearchHistoryComponent } from './aml-search-history.component';

describe('AmlSearchHistoryComponent', () => {
  let component: AmlSearchHistoryComponent;
  let fixture: ComponentFixture<AmlSearchHistoryComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AmlSearchHistoryComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(AmlSearchHistoryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
