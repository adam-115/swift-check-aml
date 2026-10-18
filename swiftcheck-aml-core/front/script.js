const fs = require('fs');
const path = require('path');

const frontDir = "D:\\swiftcheck-aml\\swiftcheck-aml\\swiftcheck-aml-core\\front";
const modelsDir = path.join(frontDir, 'src/app/models');
const servicesDir = path.join(frontDir, 'src/app/services');

// 1. Update appTypes.ts
const appTypesPath = path.join(modelsDir, 'appTypes.ts');
let appTypesContent = fs.readFileSync(appTypesPath, 'utf8');
if (!appTypesContent.includes('export interface Page')) {
    const pageInterface = "export interface Page<T> {\n    content: T[];\n    totalElements: number;\n    totalPages: number;\n    size: number;\n    number: number;\n    first: boolean;\n    last: boolean;\n}\n\n";
    fs.writeFileSync(appTypesPath, pageInterface + appTypesContent);
}

// 2. Create services
if (!fs.existsSync(servicesDir)) fs.mkdirSync(servicesDir, { recursive: true });

const services = [
    ["ApplicationConfig", "application-config", "/api/config"],
    ["ListProvider", "list-provider", "/api/providers"],
    ["Parser", "parser", "/api/parsers"],
    ["ParserExecution", "parser-execution", "/api/executions"],
    ["ParserTestExecution", "parser-test-execution", "/api/test-executions"]
];

services.forEach(([baseName, fileName, apiUrl]) => {
    const dtoName = baseName + "DTO";
    const serviceName = baseName + "Service";
    
    const content = "import { Injectable } from '@angular/core';\nimport { HttpClient, HttpParams } from '@angular/common/http';\nimport { Observable } from 'rxjs';\nimport { " + dtoName + ", Page } from '../models/appTypes';\n\n@Injectable({\n  providedIn: 'root'\n})\nexport class " + serviceName + " {\n  private apiUrl = '" + apiUrl + "';\n\n  constructor(private http: HttpClient) {}\n\n  getAll(page: number = 0, size: number = 10): Observable<Page<" + dtoName + ">> {\n    const params = new HttpParams()\n      .set('page', page.toString())\n      .set('size', size.toString());\n    return this.http.get<Page<" + dtoName + ">>(this.apiUrl, { params });\n  }\n\n  getById(id: number): Observable<" + dtoName + "> {\n    return this.http.get<" + dtoName + ">(\\/\\);\n  }\n\n  save(item: " + dtoName + "): Observable<" + dtoName + "> {\n    return this.http.post<" + dtoName + ">(this.apiUrl, item);\n  }\n\n  delete(id: number): Observable<void> {\n    return this.http.delete<void>(\\/\\);\n  }\n}\n";
    
    fs.writeFileSync(path.join(servicesDir, fileName + '.service.ts'), content);
});

// 3. Create proxy.conf.json
const proxyContent = "{\n  \"/api\": {\n    \"target\": \"http://localhost:8080\",\n    \"secure\": false\n  }\n}";
fs.writeFileSync(path.join(frontDir, 'proxy.conf.json'), proxyContent);

// 4. Update angular.json
const angularJsonPath = path.join(frontDir, 'angular.json');
let angularJson = JSON.parse(fs.readFileSync(angularJsonPath, 'utf8'));
const projectName = Object.keys(angularJson.projects)[0];
if (angularJson.projects[projectName].architect.serve) {
    angularJson.projects[projectName].architect.serve.options = angularJson.projects[projectName].architect.serve.options || {};
    angularJson.projects[projectName].architect.serve.options.proxyConfig = "proxy.conf.json";
    fs.writeFileSync(angularJsonPath, JSON.stringify(angularJson, null, 2));
}

console.log("Services and proxy configured successfully.");
