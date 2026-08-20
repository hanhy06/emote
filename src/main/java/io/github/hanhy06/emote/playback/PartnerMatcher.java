package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.Emote;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

final class PartnerMatcher {
    static final double MAX_HORIZONTAL_DISTANCE = 2.0D;
    static final double MAX_HEIGHT_DIFFERENCE = 1.0D;
    static final double MIN_FACING_DOT = Math.cos(Math.toRadians(45.0D));

    PlaybackSession find(ServerPlayer partner, String sequenceId, Collection<PlaybackSession> sessions) {
        PlaybackSession nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (PlaybackSession session : sessions) {
            if (!acceptsPartner(session, sequenceId)) {
                continue;
            }
            Candidate candidate = matchingCandidate(session, partner);
            if (candidate == null || candidate.distanceSquared() >= nearestDistanceSquared) {
                continue;
            }
            nearest = session;
            nearestDistanceSquared = candidate.distanceSquared();
        }
        return nearest;
    }

    boolean stillMatches(PlaybackSession session, ServerPlayer partner) {
        return matchingCandidate(session, partner) != null;
    }

    private static @Nullable Candidate matchingCandidate(PlaybackSession session, ServerPlayer partner) {
        PlaybackParticipant initiatorState = session.initiator();
        if (initiatorState.playerUuid().equals(partner.getUUID())) {
            return null;
        }
        ServerPlayer initiator = Emote.SERVER.getPlayerList().getPlayer(initiatorState.playerUuid());
        if (initiator == null || !initiator.isAlive() || initiator.level() != partner.level()) {
            return null;
        }
        Vec3 initiatorPosition = initiator.position();
        Vec3 partnerPosition = partner.position();
        if (!matchesGeometry(initiatorPosition, initiator.getYRot(), partnerPosition, partner.getYRot())
            || !initiator.hasLineOfSight(partner)) {
            return null;
        }
        return new Candidate(horizontalDistanceSquared(initiatorPosition, partnerPosition));
    }

    static boolean matchesGeometry(Vec3 firstPosition, float firstYaw, Vec3 secondPosition, float secondYaw) {
        double heightDifference = Math.abs(firstPosition.y - secondPosition.y);
        double horizontalDistanceSquared = horizontalDistanceSquared(firstPosition, secondPosition);
        if (heightDifference > MAX_HEIGHT_DIFFERENCE
            || horizontalDistanceSquared > MAX_HORIZONTAL_DISTANCE * MAX_HORIZONTAL_DISTANCE
            || horizontalDistanceSquared < 1.0E-8D) {
            return false;
        }

        Vec3 firstToSecond = new Vec3(
            secondPosition.x - firstPosition.x,
            0.0D,
            secondPosition.z - firstPosition.z
        ).normalize();
        Vec3 secondToFirst = firstToSecond.scale(-1.0D);
        return horizontalForward(firstYaw).dot(firstToSecond) >= MIN_FACING_DOT
            && horizontalForward(secondYaw).dot(secondToFirst) >= MIN_FACING_DOT;
    }

    private static boolean acceptsPartner(PlaybackSession session, String sequenceId) {
        return session.id().equals(sequenceId)
            && session.collaborative()
            && session.acceptsPartner();
    }

    private static Vec3 horizontalForward(float yaw) {
        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        return new Vec3(forward.x, 0.0D, forward.z).normalize();
    }

    private static double horizontalDistanceSquared(Vec3 first, Vec3 second) {
        double x = second.x - first.x;
        double z = second.z - first.z;
        return x * x + z * z;
    }

    private record Candidate(double distanceSquared) {
    }
}
