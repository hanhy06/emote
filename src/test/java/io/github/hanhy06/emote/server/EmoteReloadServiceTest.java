package io.github.hanhy06.emote.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmoteReloadServiceTest {
    @Test
    void managedResourceReloadDoesNotReadConfigTwice() {
        StubReloadOperations operations = new StubReloadOperations();
        operations.resourceReload = true;
        operations.emoteCount = 4;
        EmoteReloadService service = new EmoteReloadService(operations);

        EmoteReloadResult result = service.reloadFromCommand();
        service.handleDataPackReloadStart();

        assertEquals(1, operations.configReadCount);
        assertEquals(1, operations.packConfigReadCount);
        assertEquals(4, result.emoteCount());
    }

    @Test
    void externalResourceReloadReadsConfigAndRefreshesPlaybackState() {
        StubReloadOperations operations = new StubReloadOperations();
        operations.reloadedEmoteCount = 3;
        EmoteReloadService service = new EmoteReloadService(operations);

        service.handleDataPackReloadStart();
        service.handleDataPackReloadEnd(true);

        assertEquals(1, operations.configReadCount);
        assertEquals(1, operations.packConfigReadCount);
        assertEquals(1, operations.stopCount);
        assertEquals(1, operations.reloadCount);
        assertEquals(1, operations.syncCount);
    }

    @Test
    void reloadWithoutResourceChangeDoesNotSkipNextExternalConfigReload() {
        StubReloadOperations operations = new StubReloadOperations();
        EmoteReloadService service = new EmoteReloadService(operations);

        service.reloadFromCommand();
        service.handleDataPackReloadStart();

        assertEquals(2, operations.configReadCount);
        assertEquals(2, operations.packConfigReadCount);
        assertEquals(1, operations.reloadCount);
        assertEquals(1, operations.syncCount);
    }

    private static final class StubReloadOperations implements EmoteReloadService.ReloadOperations {
        private boolean resourceReload;
        private int emoteCount;
        private int reloadedEmoteCount;
        private int configReadCount;
        private int packConfigReadCount;
        private int reloadCount;
        private int stopCount;
        private int syncCount;

        @Override
        public boolean readConfig() {
            this.configReadCount++;
            return true;
        }

        @Override
        public boolean readPackConfig() {
            this.packConfigReadCount++;
            return true;
        }

        @Override
        public boolean enableDatapacks() {
            return this.resourceReload;
        }

        @Override
        public int reloadEmotes() {
            this.reloadCount++;
            return this.reloadedEmoteCount;
        }

        @Override
        public int emoteCount() {
            return this.emoteCount;
        }

        @Override
        public void stopAllEmotes() {
            this.stopCount++;
        }

        @Override
        public void syncAllPlayers() {
            this.syncCount++;
        }
    }
}
