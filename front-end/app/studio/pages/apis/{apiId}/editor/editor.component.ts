/**
 * @license
 * Copyright 2017 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {Component, EventEmitter, Output, Input, ViewEncapsulation} from '@angular/core';
import {ApiDefinition} from "../../../../models/api.model";
import {Oas20Document, OasLibraryUtils, OasNode, Oas20PathItem} from "oai-ts-core";


@Component({
    moduleId: module.id,
    selector: 'api-editor',
    templateUrl: 'editor.component.html',
    styleUrls: ['editor.component.css'],
    encapsulation: ViewEncapsulation.None
})
export class ApiEditorComponent {

    @Input() api: ApiDefinition;
    @Output() onDirty: EventEmitter<boolean> = new EventEmitter<boolean>();
    @Output() onSave: EventEmitter<any> = new EventEmitter<any>();

    private _library: OasLibraryUtils = new OasLibraryUtils();
    private _document: Oas20Document = null;

    theme: string = "light";
    selectedItem: string = null;
    selectedType: string = "main";

    /**
     * Constructor.
     */
    constructor() {}

    /**
     * Gets the OpenAPI spec as a document.
     */
    public document(): Oas20Document {
        if (this._document === null) {
            this._document = <Oas20Document>this._library.createDocument(this.api.spec);
        }
        return this._document;
    }

    /**
     * Returns an array of path names.
     * @return {any}
     */
    public pathNames(): string[] {
        if (this.document().paths) {
            return this.document().paths.pathItemNames();
        } else {
            return [];
        }
    }

    /**
     * Returns an array of definition names.
     * @return {any}
     */
    public definitionNames(): string[] {
        if (this.document().definitions) {
            return this.document().definitions.definitionNames();
        } else {
            return [];
        }
    }

    /**
     * Returns an array of response names.
     * @return {any}
     */
    public responseNames(): string[] {
        if (this.document().responses) {
            return this.document().responses.responseNames();
        } else {
            return [];
        }
    }

    /**
     * Called when the user selects the main/default element from the master area.
     */
    public selectMain(): void {
        this.selectedItem = null;
        this.selectedType = "main";
    }

    /**
     * Called when the user selects a path from the master area.
     * @param name
     */
    public selectPath(name: string): void {
        this.selectedItem = name;
        this.selectedType = "path";
    }

    /**
     * Called when the user selects a definition from the master area.
     * @param name
     */
    public selectDefinition(name: string): void {
        this.selectedItem = name;
        this.selectedType = "definition";

        console.info("Selected item: %s", this.selectedItem);
        console.info("Selected type: %s", this.selectedType);
    }

    /**
     * Called when the user selects a response from the master area.
     * @param name
     */
    public selectResponse(name: string): void {
        this.selectedItem = name;
        this.selectedType = "response";
    }

    /**
     * Called whenever the user presses a key.
     * @param event
     */
    public onGlobalKeyDown(event: KeyboardEvent): void {
        // TODO skip any event that was sent to an input field (e.g. input, textarea, etc)
        if (event.ctrlKey && event.key === 'z' && !event.metaKey && !event.altKey) {
            console.info("Undo!!");
        }
    }

}