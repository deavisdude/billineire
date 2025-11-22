package com.davisodom.villageoverhaul.villages.impl;

import com.davisodom.villageoverhaul.model.VolumeMask;
import org.bukkit.Location;

import java.util.List;

/**
 * Helper class for village placement collision detection.
 * R011b: Rotation-aware building placement logic.
 */
public class VillagePlacementHelper {
    
    /**
     * Compute rotated AABB bounds for a structure at given origin with specified rotation.
     * Used for collision detection BEFORE actual placement.
     * 
     * @param origin Structure origin (SW corner, ground level)
     * @param baseWidth Base structure width (X, before rotation)
     * @param baseDepth Base structure depth (Z, before rotation)
     * @param height Structure height (Y, unchanged by rotation)
     * @param rotation Rotation in degrees (0, 90, 180, or 270)
     * @return int[] {minX, maxX, minY, maxY, minZ, maxZ} - rotated AABB bounds
     */
    public static int[] computeRotatedAABB(Location origin, int baseWidth, int baseDepth, int height, int rotation) {
        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();
        
        // Calculate the 8 corners of the bounding box in schematic space (origin at 0,0,0)
        int[][] corners = new int[8][3];
        int idx = 0;
        for (int x : new int[]{0, baseWidth}) {
            for (int y : new int[]{0, height}) {
                for (int z : new int[]{0, baseDepth}) {
                    corners[idx][0] = x;
                    corners[idx][1] = y;
                    corners[idx][2] = z;
                    idx++;
                }
            }
        }
        
        // Rotate each corner around origin (0,0,0) using Y-axis rotation matrix
        int[][] rotatedCorners = new int[8][3];
        for (int i = 0; i < 8; i++) {
            int x = corners[i][0];
            int y = corners[i][1];
            int z = corners[i][2];
            
            // Apply Y-axis rotation (clockwise when viewed from above)
            switch (rotation) {
                case 0:
                    rotatedCorners[i][0] = x;
                    rotatedCorners[i][2] = z;
                    break;
                case 90:
                    rotatedCorners[i][0] = -z;
                    rotatedCorners[i][2] = x;
                    break;
                case 180:
                    rotatedCorners[i][0] = -x;
                    rotatedCorners[i][2] = -z;
                    break;
                case 270:
                    rotatedCorners[i][0] = z;
                    rotatedCorners[i][2] = -x;
                    break;
            }
            rotatedCorners[i][1] = y; // Y unchanged
        }
        
        // Find min/max of rotated corners
        int minRotX = Integer.MAX_VALUE, maxRotX = Integer.MIN_VALUE;
        int minRotY = Integer.MAX_VALUE, maxRotY = Integer.MIN_VALUE;
        int minRotZ = Integer.MAX_VALUE, maxRotZ = Integer.MIN_VALUE;
        
        for (int i = 0; i < 8; i++) {
            minRotX = Math.min(minRotX, rotatedCorners[i][0]);
            maxRotX = Math.max(maxRotX, rotatedCorners[i][0]);
            minRotY = Math.min(minRotY, rotatedCorners[i][1]);
            maxRotY = Math.max(maxRotY, rotatedCorners[i][1]);
            minRotZ = Math.min(minRotZ, rotatedCorners[i][2]);
            maxRotZ = Math.max(maxRotZ, rotatedCorners[i][2]);
        }
        
        // Translate to world coordinates
        int minX = originX + minRotX;
        int maxX = originX + maxRotX - 1; // -1 because size is exclusive
        int minY = originY + minRotY;
        int maxY = originY + maxRotY - 1;
        int minZ = originZ + minRotZ;
        int maxZ = originZ + maxRotZ - 1;
        
        return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
    }
    
    /**
     * Check if a rotated AABB intersects with any existing volume mask (with buffer).
     * 
     * @param candidateAABB Candidate structure AABB bounds
     * @param existingMasks List of existing volume masks
     * @param buffer Spacing buffer to apply around existing masks
     * @return true if collision detected, false otherwise
     */
    public static boolean checkRotatedAABBCollision(int[] candidateAABB, List<VolumeMask> existingMasks, int buffer) {
        int candMinX = candidateAABB[0];
        int candMaxX = candidateAABB[1];
        int candMinZ = candidateAABB[4];
        int candMaxZ = candidateAABB[5];
        
        for (VolumeMask mask : existingMasks) {
            // Expand mask by buffer
            int maskMinX = mask.getMinX() - buffer;
            int maskMaxX = mask.getMaxX() + buffer;
            int maskMinZ = mask.getMinZ() - buffer;
            int maskMaxZ = mask.getMaxZ() + buffer;
            
            // Check 2D XZ intersection (sufficient for building spacing)
            boolean xOverlap = candMinX <= maskMaxX && candMaxX >= maskMinX;
            boolean zOverlap = candMinZ <= maskMaxZ && candMaxZ >= maskMinZ;
            
            if (xOverlap && zOverlap) {
                return true; // Collision detected
            }
        }
        
        return false; // No collisions
    }
}
