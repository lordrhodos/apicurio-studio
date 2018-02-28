/*
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

package io.apicurio.hub.api.rest.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.apicurio.hub.api.beans.ImportApiDesign;
import io.apicurio.hub.api.beans.NewApiDesign;
import io.apicurio.hub.api.beans.NewApiPublication;
import io.apicurio.hub.api.beans.ResourceContent;
import io.apicurio.hub.api.beans.UpdateCollaborator;
import io.apicurio.hub.api.connectors.ISourceConnector;
import io.apicurio.hub.api.connectors.SourceConnectorException;
import io.apicurio.hub.api.connectors.SourceConnectorFactory;
import io.apicurio.hub.api.metrics.IApiMetrics;
import io.apicurio.hub.api.rest.IDesignsResource;
import io.apicurio.hub.api.security.ISecurityContext;
import io.apicurio.hub.core.beans.ApiContentType;
import io.apicurio.hub.core.beans.ApiDesign;
import io.apicurio.hub.core.beans.ApiDesignChange;
import io.apicurio.hub.core.beans.ApiDesignCollaborator;
import io.apicurio.hub.core.beans.ApiDesignCommand;
import io.apicurio.hub.core.beans.ApiDesignContent;
import io.apicurio.hub.core.beans.ApiDesignResourceInfo;
import io.apicurio.hub.core.beans.ApiPublication;
import io.apicurio.hub.core.beans.Contributor;
import io.apicurio.hub.core.beans.FormatType;
import io.apicurio.hub.core.beans.Invitation;
import io.apicurio.hub.core.beans.LinkedAccountType;
import io.apicurio.hub.core.beans.OpenApi2Document;
import io.apicurio.hub.core.beans.OpenApi3Document;
import io.apicurio.hub.core.beans.OpenApiDocument;
import io.apicurio.hub.core.beans.OpenApiInfo;
import io.apicurio.hub.core.editing.IEditingSessionManager;
import io.apicurio.hub.core.exceptions.AccessDeniedException;
import io.apicurio.hub.core.exceptions.NotFoundException;
import io.apicurio.hub.core.exceptions.ServerError;
import io.apicurio.hub.core.js.OaiCommandException;
import io.apicurio.hub.core.js.OaiCommandExecutor;
import io.apicurio.hub.core.storage.IStorage;
import io.apicurio.hub.core.storage.StorageException;
import io.apicurio.hub.core.util.FormatUtils;

/**
 * @author eric.wittmann@gmail.com
 */
@ApplicationScoped
public class DesignsResource implements IDesignsResource {

    private static Logger logger = LoggerFactory.getLogger(DesignsResource.class);
    private static ObjectMapper mapper = new ObjectMapper();
    static {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(Include.NON_NULL);
    }

    @Inject
    private IStorage storage;
    @Inject
    private SourceConnectorFactory sourceConnectorFactory;
    @Inject
    private ISecurityContext security;
    @Inject
    private IApiMetrics metrics;
    @Inject
    private OaiCommandExecutor oaiCommandExecutor;
    @Inject
    private IEditingSessionManager editingSessionManager;

    @Context
    private HttpServletRequest request;
    @Context
    private HttpServletResponse response;

    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#listDesigns()
     */
    @Override
    public Collection<ApiDesign> listDesigns() throws ServerError {
        metrics.apiCall("/designs", "GET");
        
        try {
            logger.debug("Listing API Designs");
            String user = this.security.getCurrentUser().getLogin();
            Collection<ApiDesign> designs = this.storage.listApiDesigns(user);
            return designs;
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }

    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#importDesign(io.apicurio.hub.api.beans.ImportApiDesign)
     */
    @Override
    public ApiDesign importDesign(ImportApiDesign info) throws ServerError, NotFoundException {
        metrics.apiCall("/designs", "PUT");
        
        if (info.getData() != null && !info.getData().trim().isEmpty()) {
            logger.debug("Importing an API Design (from data).");
            return importDesignFromData(info);
        } else {
            logger.debug("Importing an API Design: {}", info.getUrl());
            ISourceConnector connector = null;
            
            try {
                connector = this.sourceConnectorFactory.createConnector(info.getUrl());
            } catch (NotFoundException nfe) {
                // This means it's not a source control URL.  So we'll treat it as a raw content URL.
                connector = null;
            }
            
            if (connector != null) {
                return importDesignFromSource(info, connector);
            } else {
                return importDesignFromUrl(info);
            }
        }
    }

    /**
     * Imports an API Design from one of the source control systems using its API.
     * @param info
     * @param connector
     * @throws NotFoundException
     * @throws ServerError 
     */
    private ApiDesign importDesignFromSource(ImportApiDesign info, ISourceConnector connector) throws NotFoundException, ServerError {
        try {
            ApiDesignResourceInfo resourceInfo = connector.validateResourceExists(info.getUrl());
            ResourceContent initialApiContent = connector.getResourceContent(info.getUrl());
            
            Date now = new Date();
            String user = this.security.getCurrentUser().getLogin();
            String description = resourceInfo.getDescription();
            if (description == null) {
                description = "";
            }

            ApiDesign design = new ApiDesign();
            design.setName(resourceInfo.getName());
            design.setDescription(description);
            design.setCreatedBy(user);
            design.setCreatedOn(now);
            design.setTags(resourceInfo.getTags());
            
            try {
                String content = initialApiContent.getContent();
                if (resourceInfo.getFormat() == FormatType.YAML) {
                    content = FormatUtils.yamlToJson(content);
                }
                String id = this.storage.createApiDesign(user, design, content);
                design.setId(id);
            } catch (StorageException e) {
                throw new ServerError(e);
            }
            
            metrics.apiImport(connector.getType());
            
            return design;
        } catch (SourceConnectorException | IOException e) {
            throw new ServerError(e);
        }
    }

    /**
     * Imports an API Design from base64 encoded content included in the request.  This supports
     * the use-case where the UI allows the user to simply copy/paste the full API content.
     * @param info
     * @throws ServerError
     */
    private ApiDesign importDesignFromData(ImportApiDesign info) throws ServerError {
        try {
            String data = info.getData();
            byte[] decodedData = Base64.decodeBase64(data);
            
            try (InputStream is = new ByteArrayInputStream(decodedData)) {
                String content = IOUtils.toString(is);
                ApiDesignResourceInfo resourceInfo = ApiDesignResourceInfo.fromContent(content);
                
                String name = resourceInfo.getName();
                if (name == null) {
                    name = "Imported API Design";
                }

                Date now = new Date();
                String user = this.security.getCurrentUser().getLogin();
    
                ApiDesign design = new ApiDesign();
                design.setName(name);
                design.setDescription(resourceInfo.getDescription());
                design.setCreatedBy(user);
                design.setCreatedOn(now);
                design.setTags(resourceInfo.getTags());
    
                try {
                    if (resourceInfo.getFormat() == FormatType.YAML) {
                        content = FormatUtils.yamlToJson(content);
                    }
                    String id = this.storage.createApiDesign(user, design, content);
                    design.setId(id);
                } catch (StorageException e) {
                    throw new ServerError(e);
                }
                
                metrics.apiImport(null);
                
                return design;
            }
        } catch (IOException e) {
            throw new ServerError(e);
        } catch (Exception e) {
            throw new ServerError(e);
        }
    }

    /**
     * Imports an API design from an arbitrary URL.  This simply opens a connection to that 
     * URL and tries to consume its content as an OpenAPI document.
     * @param info
     * @throws NotFoundException
     * @throws ServerError
     */
    private ApiDesign importDesignFromUrl(ImportApiDesign info) throws NotFoundException, ServerError {
        try {
            URL url = new URL(info.getUrl());
            
            try (InputStream is = url.openStream()) {
                String content = IOUtils.toString(is);
                ApiDesignResourceInfo resourceInfo = ApiDesignResourceInfo.fromContent(content);
                
                String name = resourceInfo.getName();
                if (name == null) {
                    name = url.getPath();
                    if (name != null && name.indexOf("/") >= 0) {
                        name = name.substring(name.indexOf("/") + 1);
                    }
                }
                if (name == null) {
                    name = "Imported API Design";
                }
    
                Date now = new Date();
                String user = this.security.getCurrentUser().getLogin();
    
                ApiDesign design = new ApiDesign();
                design.setName(name);
                design.setDescription(resourceInfo.getDescription());
                design.setCreatedBy(user);
                design.setCreatedOn(now);
                design.setTags(resourceInfo.getTags());
    
                try {
                    if (resourceInfo.getFormat() == FormatType.YAML) {
                        content = FormatUtils.yamlToJson(content);
                    }
                    String id = this.storage.createApiDesign(user, design, content);
                    design.setId(id);
                } catch (StorageException e) {
                    throw new ServerError(e);
                }
                
                metrics.apiImport(null);
                
                return design;
            }
        } catch (IOException e) {
            throw new ServerError(e);
        } catch (Exception e) {
            throw new ServerError(e);
        }
    }

    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#createDesign(io.apicurio.hub.api.beans.NewApiDesign)
     */
    @Override
    public ApiDesign createDesign(NewApiDesign info) throws ServerError {
        logger.debug("Creating an API Design: {}", info.getName());
        metrics.apiCall("/designs", "POST");

        try {
            Date now = new Date();
            String user = this.security.getCurrentUser().getLogin();
            
            // The API Design meta-data
            ApiDesign design = new ApiDesign();
            design.setName(info.getName());
            design.setDescription(info.getDescription());
            design.setCreatedBy(user);
            design.setCreatedOn(now);

            // The API Design content (OAI document)
            OpenApiDocument doc;
            if (info.getSpecVersion() == null || info.getSpecVersion().equals("2.0")) {
                doc = new OpenApi2Document();
            } else {
                doc = new OpenApi3Document();
            }
            doc.setInfo(new OpenApiInfo());
            doc.getInfo().setTitle(info.getName());
            doc.getInfo().setDescription(info.getDescription());
            doc.getInfo().setVersion("1.0.0");
            String oaiContent = mapper.writeValueAsString(doc);

            // Create the API Design in the database
            String designId = storage.createApiDesign(user, design, oaiContent);
            design.setId(designId);
            
            metrics.apiCreate(info.getSpecVersion());
            
            return design;
        } catch (JsonProcessingException | StorageException e) {
            throw new ServerError(e);
        }
    }

    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#getDesign(java.lang.String)
     */
    @Override
    public ApiDesign getDesign(String designId) throws ServerError, NotFoundException {
        logger.debug("Getting an API design with ID {}", designId);
        metrics.apiCall("/designs/{designId}", "GET");

        try {
            String user = this.security.getCurrentUser().getLogin();
            ApiDesign design = this.storage.getApiDesign(user, designId);
            return design;
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }

    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#editDesign(java.lang.String)
     */
    @Override
    public Response editDesign(String designId) throws ServerError, NotFoundException {
        logger.debug("Editing an API Design with ID {}", designId);
        metrics.apiCall("/designs/{designId}", "PUT");
        
        try {
            String user = this.security.getCurrentUser().getLogin();
            logger.debug("\tUSER: {}", user);

            ApiDesignContent designContent = this.storage.getLatestContentDocument(user, designId);
            String content = designContent.getOaiDocument();
            long contentVersion = designContent.getContentVersion();
            String secret = this.security.getToken().substring(0, Math.min(64, this.security.getToken().length() - 1));
            String sessionId = this.editingSessionManager.createSessionUuid(designId, user, secret, contentVersion);

            logger.debug("\tCreated Session ID: {}", sessionId);
            logger.debug("\t            Secret: {}", secret);

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            String ct = "application/json; charset=" + StandardCharsets.UTF_8;
            String cl = String.valueOf(bytes.length);

            ResponseBuilder builder = Response.ok().entity(content)
                    .header("X-Apicurio-EditingSessionUuid", sessionId)
                    .header("X-Apicurio-ContentVersion", contentVersion)
                    .header("Content-Type", ct)
                    .header("Content-Length", cl);

            return builder.build();
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }

    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#deleteDesign(java.lang.String)
     */
    @Override
    public void deleteDesign(String designId) throws ServerError, NotFoundException {
        logger.debug("Deleting an API Design with ID {}", designId);
        metrics.apiCall("/designs/{designId}", "DELETE");
        
        try {
            String user = this.security.getCurrentUser().getLogin();
            this.storage.deleteApiDesign(user, designId);
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#getContributors(java.lang.String)
     */
    @Override
    public Collection<Contributor> getContributors(String designId) throws ServerError, NotFoundException {
        logger.debug("Retrieving contributors list for design with ID: {}", designId);
        metrics.apiCall("/designs/{designId}/contributors", "GET");

        try {
            String user = this.security.getCurrentUser().getLogin();
            return this.storage.listContributors(user, designId);
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#getContent(java.lang.String)
     */
    @Override
    public Response getContent(String designId, String format) throws ServerError, NotFoundException {
        logger.debug("Getting content for API design with ID: {}", designId);
        metrics.apiCall("/designs/{designId}/content", "GET");

        try {
            String user = this.security.getCurrentUser().getLogin();
            ApiDesignContent designContent = this.storage.getLatestContentDocument(user, designId);
            List<ApiDesignCommand> apiCommands = this.storage.listContentCommands(user, designId, designContent.getContentVersion());
            List<String> commands = new ArrayList<>(apiCommands.size());
            for (ApiDesignCommand apiCommand : apiCommands) {
                commands.add(apiCommand.getCommand());
            }
            String content = this.oaiCommandExecutor.executeCommands(designContent.getOaiDocument(), commands);
            String ct = "application/json; charset=" + StandardCharsets.UTF_8;
            String cl = null;
            
            // Convert to yaml if necessary
            if ("yaml".equals(format)) {
                content = FormatUtils.jsonToYaml(content);
                ct = "application/x-yaml; charset=" + StandardCharsets.UTF_8;
            }
            
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            cl = String.valueOf(bytes.length);
            
            ResponseBuilder builder = Response.ok().entity(content)
                    .header("Content-Type", ct)
                    .header("Content-Length", cl);
            return builder.build();
        } catch (StorageException | OaiCommandException | IOException e) {
            throw new ServerError(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#createInvitation(java.lang.String)
     */
    @Override
    public Invitation createInvitation(String designId) throws ServerError, NotFoundException, AccessDeniedException {
        logger.debug("Creating a collaboration invitation for API: {} ", designId);
        metrics.apiCall("/designs/{designId}/invitations", "POST");

        try {
            String user = this.security.getCurrentUser().getLogin();
            String username = this.security.getCurrentUser().getName();
            String inviteId = UUID.randomUUID().toString();
            
            ApiDesign design = this.storage.getApiDesign(user, designId);
            if (!this.storage.hasOwnerPermission(user, designId)) {
                throw new AccessDeniedException();
            }
            
            this.storage.createCollaborationInvite(inviteId, designId, user, username, "collaborator", design.getName());
            Invitation invite = new Invitation();
            invite.setCreatedBy(user);
            invite.setCreatedOn(new Date());
            invite.setDesignId(designId);
            invite.setInviteId(inviteId);
            invite.setStatus("pending");
            return invite;
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }

    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#getInvitation(java.lang.String, java.lang.String)
     */
    @Override
    public Invitation getInvitation(String designId, String inviteId) throws ServerError, NotFoundException {
        logger.debug("Retrieving a collaboration invitation for API: {}  and inviteID: {}", designId, inviteId);
        metrics.apiCall("/designs/{designId}/invitations/{inviteId}", "GET");

        try {
            return this.storage.getCollaborationInvite(designId, inviteId);
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#getInvitations(java.lang.String)
     */
    @Override
    public Collection<Invitation> getInvitations(String designId) throws ServerError, NotFoundException {
        logger.debug("Retrieving all collaboration invitations for API: {}", designId);
        metrics.apiCall("/designs/{designId}/invitations", "GET");

        try {
            String user = this.security.getCurrentUser().getLogin();
            return this.storage.listCollaborationInvites(designId, user);
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#acceptInvitation(java.lang.String, java.lang.String)
     */
    @Override
    public void acceptInvitation(String designId, String inviteId) throws ServerError, NotFoundException {
        logger.debug("Accepting an invitation to collaborate on an API: {}", designId);
        metrics.apiCall("/designs/{designId}/invitations", "PUT");

        try {
            String user = this.security.getCurrentUser().getLogin();
            Invitation invite = this.storage.getCollaborationInvite(designId, inviteId);
            if (this.storage.hasWritePermission(user, designId)) {
                throw new NotFoundException();
            }
            boolean accepted = this.storage.updateCollaborationInviteStatus(inviteId, "pending", "accepted", user);
            if (!accepted) {
                throw new NotFoundException();
            }
            this.storage.createPermission(designId, user, invite.getRole());
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }

    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#rejectInvitation(java.lang.String, java.lang.String)
     */
    @Override
    public void rejectInvitation(String designId, String inviteId) throws ServerError, NotFoundException {
        logger.debug("Rejecting an invitation to collaborate on an API: {}", designId);
        metrics.apiCall("/designs/{designId}/invitations", "DELETE");

        try {
            String user = this.security.getCurrentUser().getLogin();
            // This will ensure that the invitation exists for this designId.
            this.storage.getCollaborationInvite(designId, inviteId);
            boolean accepted = this.storage.updateCollaborationInviteStatus(inviteId, "pending", "rejected", user);
            if (!accepted) {
                throw new NotFoundException();
            }
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }

    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#getCollaborators(java.lang.String)
     */
    @Override
    public Collection<ApiDesignCollaborator> getCollaborators(String designId) throws ServerError, NotFoundException {
        logger.debug("Retrieving all collaborators for API: {}", designId);
        metrics.apiCall("/designs/{designId}/collaborators", "GET");

        try {
            String user = this.security.getCurrentUser().getLogin();
            if (!this.storage.hasWritePermission(user, designId)) {
                throw new NotFoundException();
            }
            return this.storage.listPermissions(designId);
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#updateCollaborator(java.lang.String, java.lang.String, io.apicurio.hub.api.beans.UpdateCollaborator)
     */
    @Override
    public void updateCollaborator(String designId, String userId,
            UpdateCollaborator update) throws ServerError, NotFoundException, AccessDeniedException {
        logger.debug("Updating collaborator for API: {}", designId);
        metrics.apiCall("/designs/{designId}/collaborators/{userId}", "PUT");

        try {
            String user = this.security.getCurrentUser().getLogin();
            if (!this.storage.hasOwnerPermission(user, designId)) {
                throw new AccessDeniedException();
            }
            this.storage.updatePermission(designId, userId, update.getNewRole());
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#deleteCollaborator(java.lang.String, java.lang.String)
     */
    @Override
    public void deleteCollaborator(String designId, String userId)
            throws ServerError, NotFoundException, AccessDeniedException {
        logger.debug("Deleting/revoking collaborator for API: {}", designId);
        metrics.apiCall("/designs/{designId}/collaborators/{userId}", "DELETE");

        try {
            String user = this.security.getCurrentUser().getLogin();
            if (!this.storage.hasOwnerPermission(user, designId)) {
                throw new AccessDeniedException();
            }
            this.storage.deletePermission(designId, userId);
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }

    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#getActivity(java.lang.String, java.lang.Integer, java.lang.Integer)
     */
    @Override
    public Collection<ApiDesignChange> getActivity(String designId, Integer start, Integer end)
            throws ServerError, NotFoundException {
        int from = 0;
        int to = 20;
        if (start != null) {
            from = start.intValue();
        }
        if (end != null) {
            to = end.intValue();
        }
        
        try {
            String user = this.security.getCurrentUser().getLogin();
            if (!this.storage.hasWritePermission(user, designId)) {
                throw new NotFoundException();
            }
            return this.storage.listApiDesignActivity(designId, from, to);
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#getPublications(java.lang.String, java.lang.Integer, java.lang.Integer)
     */
    @Override
    public Collection<ApiPublication> getPublications(String designId, Integer start, Integer end)
            throws ServerError, NotFoundException {
        int from = 0;
        int to = 20;
        if (start != null) {
            from = start.intValue();
        }
        if (end != null) {
            to = end.intValue();
        }
        
        try {
            String user = this.security.getCurrentUser().getLogin();
            if (!this.storage.hasWritePermission(user, designId)) {
                throw new NotFoundException();
            }
            return this.storage.listApiDesignPublications(designId, from, to);
        } catch (StorageException e) {
            throw new ServerError(e);
        }
    }
    
    /**
     * @see io.apicurio.hub.api.rest.IDesignsResource#publishApi(java.lang.String, io.apicurio.hub.api.beans.NewApiPublication)
     */
    @Override
    public void publishApi(String designId, NewApiPublication info) throws ServerError, NotFoundException {
        LinkedAccountType type = info.getType();
        
        try {
            // First step - publish the content to the soruce control system
            ISourceConnector connector = this.sourceConnectorFactory.createConnector(type);
            String resourceUrl = info.toResourceUrl();
            String formattedContent = getApiContent(designId, info.getFormat());
            try {
                ResourceContent content = connector.getResourceContent(resourceUrl);
                content.setContent(formattedContent);
                connector.updateResourceContent(resourceUrl, info.getCommitMessage(), null, content);
            } catch (NotFoundException nfe) {
                connector.createResourceContent(resourceUrl, info.getCommitMessage(), formattedContent);
            }
            
            // Followup step - store a row in the api_content table
            try {
                String user = this.security.getCurrentUser().getLogin();
                String publicationData = createPublicationData(info);
                storage.addContent(user, designId, ApiContentType.Publish, publicationData);
            } catch (Exception e) {
                logger.error("Failed to record API publication in database.", e);
            }
        } catch (SourceConnectorException e) {
            throw new ServerError(e);
        }
    }

    /**
     * Creates the JSON data to be stored in the data row representing a "publish API" event
     * (also known as an API publication).
     * @param info
     */
    private String createPublicationData(NewApiPublication info) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.set("type", JsonNodeFactory.instance.textNode(info.getType().name()));
            data.set("org", JsonNodeFactory.instance.textNode(info.getOrg()));
            data.set("repo", JsonNodeFactory.instance.textNode(info.getOrg()));
            data.set("team", JsonNodeFactory.instance.textNode(info.getOrg()));
            data.set("group", JsonNodeFactory.instance.textNode(info.getOrg()));
            data.set("project", JsonNodeFactory.instance.textNode(info.getOrg()));
            data.set("branch", JsonNodeFactory.instance.textNode(info.getOrg()));
            data.set("resource", JsonNodeFactory.instance.textNode(info.getOrg()));
            data.set("format", JsonNodeFactory.instance.textNode(info.getFormat().name()));
            data.set("commitMessage", JsonNodeFactory.instance.textNode(info.getCommitMessage()));
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the current content of an API.
     * @param designId
     * @param format
     * @throws ServerError
     * @throws NotFoundException
     */
    private String getApiContent(String designId, FormatType format) throws ServerError, NotFoundException {
        try {
            String user = this.security.getCurrentUser().getLogin();
            ApiDesignContent designContent = this.storage.getLatestContentDocument(user, designId);
            List<ApiDesignCommand> apiCommands = this.storage.listContentCommands(user, designId, designContent.getContentVersion());
            List<String> commands = new ArrayList<>(apiCommands.size());
            for (ApiDesignCommand apiCommand : apiCommands) {
                commands.add(apiCommand.getCommand());
            }
            String content = this.oaiCommandExecutor.executeCommands(designContent.getOaiDocument(), commands);
            
            // Convert to yaml if necessary
            if (format == FormatType.YAML) {
                content = FormatUtils.jsonToYaml(content);
            }
            
            return content;
        } catch (StorageException | OaiCommandException | IOException e) {
            throw new ServerError(e);
        }
    }
}
