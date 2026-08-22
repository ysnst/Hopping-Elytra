package net.minto.hoppingelytra;

/**
 * リモートサーバーでの滑空再開要求を直列化する状態機械。
 * Minecraft固有の判定とパケット送信は呼び出し側に残し、時間経過だけでは再送しない。
 */
final class RemoteGlidingRecovery {
    static final int DEFAULT_MAX_REQUESTS = 4;
    static final int DEFAULT_MAX_RECOVERY_TICKS = 60;
    static final int DEFAULT_MAX_GROUNDED_TICKS = 15;

    enum State {
        IDLE,
        REQUEST_IN_FLIGHT,
        WAIT_AIRBORNE,
        CONFIRMED
    }

    enum Action {
        NONE,
        SEND_REQUEST
    }

    private final int maxRequests;
    private final int maxRecoveryTicks;
    private final int maxGroundedTicks;

    private State state = State.IDLE;
    private int requestsSent;
    private int recoveryTicks;
    private int groundedTicks;

    RemoteGlidingRecovery() {
        this(DEFAULT_MAX_REQUESTS, DEFAULT_MAX_RECOVERY_TICKS, DEFAULT_MAX_GROUNDED_TICKS);
    }

    RemoteGlidingRecovery(int maxRequests, int maxRecoveryTicks, int maxGroundedTicks) {
        if (maxRequests <= 0 || maxRecoveryTicks <= 0 || maxGroundedTicks <= 0) {
            throw new IllegalArgumentException("Recovery limits must be positive");
        }

        this.maxRequests = maxRequests;
        this.maxRecoveryTicks = maxRecoveryTicks;
        this.maxGroundedTicks = maxGroundedTicks;
    }

    Action begin() {
        if (state != State.IDLE) {
            return Action.NONE;
        }

        requestsSent = 0;
        recoveryTicks = 0;
        groundedTicks = 0;
        return issueRequest();
    }

    Action tick(boolean airborne, boolean continuationValid) {
        if (state == State.IDLE) {
            return Action.NONE;
        }

        if (!continuationValid) {
            reset();
            return Action.NONE;
        }

        // 通常の着地をまたぐ間は維持するが、地上に留まった場合は次の操作へ持ち越さない。
        if (airborne) {
            groundedTicks = 0;
        } else if (++groundedTicks >= maxGroundedTicks) {
            reset();
            return Action.NONE;
        }

        // 成功確認中は長時間の連続hopを許可し、回復中だけ時間上限を消費する。
        if (state != State.CONFIRMED && ++recoveryTicks >= maxRecoveryTicks) {
            reset();
            return Action.NONE;
        }

        if (state == State.WAIT_AIRBORNE && airborne) {
            return issueRequest();
        }

        return Action.NONE;
    }

    Action onServerGlidingState(boolean gliding, boolean airborne, boolean continuationValid) {
        // 終了後の遅延応答や通常滑空の同期は、セッションを開始する根拠にしない。
        if (state == State.IDLE) {
            return Action.NONE;
        }

        if (!continuationValid) {
            reset();
            return Action.NONE;
        }

        if (gliding) {
            state = State.CONFIRMED;
            requestsSent = 0;
            recoveryTicks = 0;
            groundedTicks = 0;
            return Action.NONE;
        }

        // CONFIRMED後のfalseは次の着地・hopに対応する新しい回復区間として扱う。
        if (state == State.CONFIRMED) {
            requestsSent = 0;
            recoveryTicks = 0;
        }

        if (requestsSent >= maxRequests) {
            reset();
            return Action.NONE;
        }

        if (!airborne) {
            state = State.WAIT_AIRBORNE;
            return Action.NONE;
        }

        return issueRequest();
    }

    void reset() {
        state = State.IDLE;
        requestsSent = 0;
        recoveryTicks = 0;
        groundedTicks = 0;
    }

    State state() {
        return state;
    }

    int requestsSent() {
        return requestsSent;
    }

    int recoveryTicks() {
        return recoveryTicks;
    }

    boolean isActive() {
        return state != State.IDLE;
    }

    private Action issueRequest() {
        if (requestsSent >= maxRequests) {
            reset();
            return Action.NONE;
        }

        requestsSent++;
        state = State.REQUEST_IN_FLIGHT;
        return Action.SEND_REQUEST;
    }
}
