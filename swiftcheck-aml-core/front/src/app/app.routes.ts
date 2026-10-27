import { Routes } from '@angular/router';
import { ParserComponent } from './components/parser/parser.component';
import { ParserExecutionsComponent } from './components/parser-executions/parser-executions.component';
import { ExecutionDetailComponent } from './components/execution-detail/execution-detail.component';
import { ParserTestsComponent } from './components/parser-tests/parser-tests.component';
import { ParserTestDetailComponent } from './components/parser-test-detail/parser-test-detail.component';
import { AppConfigComponent } from './components/app-config/app-config.component';
import { AmlVerificationComponent } from './components/aml-verification/aml-verification.component';
import { WikiComponent } from './components/wiki/wiki.component';
import { AmlSearchHistoryComponent } from './components/aml-search-history/aml-search-history.component';
import { AmlAdvancedVerificationComponent } from './components/aml-advanced-verification/aml-advanced-verification.component';
import { PepCoverageComponent } from './components/pep-coverage/pep-coverage.component';
import { PepCategoriesComponent } from './components/pep-categories/pep-categories.component';
import { ParserAlertsComponent } from './components/parser-alerts/parser-alerts.component';
import { authGuard } from './guards/auth.guard';

export const routes: Routes = [
  { path: 'wiki', component: WikiComponent, canActivate: [authGuard] },
  { path: 'parsers', component: ParserComponent, canActivate: [authGuard], data: { roles: ['admin', 'tester'] } },
  { path: 'parsers/:id/executions', component: ParserExecutionsComponent, canActivate: [authGuard], data: { roles: ['admin', 'tester'] } },
  { path: 'executions', component: ParserExecutionsComponent, canActivate: [authGuard], data: { roles: ['admin', 'tester'] } },
  { path: 'executions/:id', component: ExecutionDetailComponent, canActivate: [authGuard], data: { roles: ['admin', 'tester'] } },
  { path: 'tests', component: ParserTestsComponent, canActivate: [authGuard], data: { roles: ['admin', 'tester'] } },
  { path: 'tests/:id', component: ParserTestDetailComponent, canActivate: [authGuard], data: { roles: ['admin', 'tester'] } },
  { path: 'verification', component: AmlVerificationComponent, canActivate: [authGuard], data: { roles: ['admin', 'tester'] } },
  { path: 'verification/advanced', component: AmlAdvancedVerificationComponent, canActivate: [authGuard], data: { roles: ['admin', 'tester'] } },
  { path: 'verification/history', component: AmlSearchHistoryComponent, canActivate: [authGuard], data: { roles: ['admin', 'tester'] } },
  { path: 'coverage', component: PepCoverageComponent, canActivate: [authGuard], data: { roles: ['admin', 'tester'] } },
  { path: 'pep-categories', component: PepCategoriesComponent, canActivate: [authGuard], data: { roles: ['admin'] } },
  { path: 'alerts', component: ParserAlertsComponent, canActivate: [authGuard], data: { roles: ['admin', 'tester'] } },
  // Configuration système réservée exclusivement au rôle ADMIN
  { path: 'config', component: AppConfigComponent, canActivate: [authGuard], data: { roles: ['admin'] } },
  { path: '', redirectTo: 'wiki', pathMatch: 'full' }
];