package com.davisodom.villageoverhaul.projects;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Service for managing village projects and tracking contributions.
 * 
 * Responsibilities:
 * - Thread-safe project registry
 * - Contribution routing and validation
 * - Progress tracking and completion detection
 * - Audit logging for all contributions
 * 
 * Thread-safe for concurrent access.
 */
public class ProjectService {
    
    private final Logger logger;
    private final Map<UUID, Project> projects;
    private final Map<UUID, List<UUID>> villageProjects; // villageId → projectIds
    private final List<ContributionAuditEntry> auditLog;
    
    public ProjectService(Logger logger) {
        this.logger = logger;
        this.projects = new ConcurrentHashMap<>();
        this.villageProjects = new ConcurrentHashMap<>();
        this.auditLog = Collections.synchronizedList(new ArrayList<>());
    }
    
    /**
     * Create a new project for a village
     * 
     * @param villageId Village that owns the project
     * @param buildingRef Reference to building definition
     * @param costMillz Total cost in Millz
     * @param unlockEffects Effects applied on completion
     * @return Created project
     */
    public Project createProject(UUID villageId, String buildingRef, long costMillz, List<String> unlockEffects) {
        Project project = new Project(villageId, buildingRef, costMillz, unlockEffects);
        projects.put(project.getId(), project);
        
        villageProjects.computeIfAbsent(villageId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(project.getId());
        
        logger.info("Created project: " + project);
        return project;
    }
    
    /**
     * Load a project from persistence
     */
    public void loadProject(UUID id, UUID villageId, String buildingRef, long costMillz,
                           long progressMillz, Project.Status status, Map<UUID, Long> contributors,
                           List<String> unlockEffects, java.time.Instant createdAt, java.time.Instant completedAt) {
        Project project = new Project(id, villageId, buildingRef, costMillz, progressMillz,
                status, contributors, unlockEffects, createdAt, completedAt);
        projects.put(id, project);
        
        villageProjects.computeIfAbsent(villageId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(id);
    }
    
    /**
     * Get a project by ID
     */
    public Optional<Project> getProject(UUID projectId) {
        return Optional.ofNullable(projects.get(projectId));
    }
    
    /**
     * Get all projects for a village
     */
    public List<Project> getVillageProjects(UUID villageId) {
        List<UUID> projectIds = villageProjects.get(villageId);
        if (projectIds == null) {
            return Collections.emptyList();
        }
        
        return projectIds.stream()
                .map(projects::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    
    /**
     * Get all active projects for a village
     */
    public List<Project> getActiveVillageProjects(UUID villageId) {
        return getVillageProjects(villageId).stream()
                .filter(p -> p.getStatus() == Project.Status.ACTIVE)
                .collect(Collectors.toList());
    }
    
    /**
     * Get all projects (for admin/persistence)
     */
    public Collection<Project> getAllProjects() {
        return Collections.unmodifiableCollection(projects.values());
    }
    
    /**
     * Activate a project (make it visible and accepting contributions)
     * 
     * @param projectId Project to activate
     * @return true if activated, false if not found or already active
     */
    public boolean activateProject(UUID projectId) {
        Project project = projects.get(projectId);
        if (project == null) {
            logger.warning("Cannot activate unknown project: " + projectId);
            return false;
        }
        
        boolean activated = project.activate();
        if (activated) {
            logger.info("Activated project: " + project);
        }
        return activated;
    }
    
    /**
     * Contribute to a project from a player
     * 
     * @param projectId Project receiving the contribution
     * @param playerId Player making the contribution
     * @param millz Amount in Millz
     * @return Contribution result, or empty if project not found
     */
    public Optional<Project.ContributionResult> contribute(UUID projectId, UUID playerId, long millz) {
        Project project = projects.get(projectId);
        if (project == null) {
            logger.warning("Cannot contribute to unknown project: " + projectId);
            return Optional.empty();
        }
        
        try {
            Project.ContributionResult result = project.contribute(playerId, millz);
            
            // Audit log
            ContributionAuditEntry entry = new ContributionAuditEntry(
                    java.time.Instant.now(),
                    projectId,
                    project.getVillageId(),
                    playerId,
                    millz,
                    result.getAccepted(),
                    result.getOverflow(),
                    project.getProgressMillz(),
                    project.getCostMillz(),
                    result.isCompleted()
            );
            auditLog.add(entry);
            
            logger.info(String.format("Contribution: player=%s project=%s accepted=%d overflow=%d completed=%s",
                    playerId, projectId, result.getAccepted(), result.getOverflow(), result.isCompleted()));
            
            if (result.isCompleted()) {
                logger.info("OK Project completed: " + project);
            }
            
            return Optional.of(result);
            
        } catch (Exception e) {
            logger.severe("Failed to process contribution: " + e.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * Get contribution audit log
     */
    public List<ContributionAuditEntry> getAuditLog() {
        return Collections.unmodifiableList(new ArrayList<>(auditLog));
    }
    
    /**
     * Get contributions for a specific player
     */
    public List<ContributionAuditEntry> getPlayerContributions(UUID playerId) {
        return auditLog.stream()
                .filter(e -> e.playerId.equals(playerId))
                .collect(Collectors.toList());
    }
    
    /**
     * Audit entry for a contribution
     */
    public static class ContributionAuditEntry {
        public final java.time.Instant timestamp;
        public final UUID projectId;
        public final UUID villageId;
        public final UUID playerId;
        public final long attemptedMillz;
        public final long acceptedMillz;
        public final long overflowMillz;
        public final long progressAfter;
        public final long costMillz;
        public final boolean completed;
        
        public ContributionAuditEntry(java.time.Instant timestamp, UUID projectId, UUID villageId,
                                     UUID playerId, long attemptedMillz, long acceptedMillz,
                                     long overflowMillz, long progressAfter, long costMillz,
                                     boolean completed) {
            this.timestamp = timestamp;
            this.projectId = projectId;
            this.villageId = villageId;
            this.playerId = playerId;
            this.attemptedMillz = attemptedMillz;
            this.acceptedMillz = acceptedMillz;
            this.overflowMillz = overflowMillz;
            this.progressAfter = progressAfter;
            this.costMillz = costMillz;
            this.completed = completed;
        }
        
        @Override
        public String toString() {
            return String.format("ContributionAudit{time=%s, player=%s, project=%s, accepted=%d/%d, overflow=%d, progress=%d/%d, completed=%s}",
                    timestamp, playerId, projectId, acceptedMillz, attemptedMillz, overflowMillz,
                    progressAfter, costMillz, completed);
        }
    }
}

