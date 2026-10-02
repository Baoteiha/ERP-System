package vn.essvn.erpcafe.identity.domain;

/**
 * Class: AuthEventType
 * Purpose: What an entry of the authentication audit trail records.
 */
public enum AuthEventType {
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    TOKEN_REFRESHED,
    /** A refresh token presented again after it was rotated away; its whole family was revoked. */
    REFRESH_REUSE_DETECTED,
    LOGGED_OUT,
    ACCESS_GRANTED,
    ACCESS_REVOKED,
    /** The user changed their own password. */
    PASSWORD_CHANGED,
    /** The password was set for the user: by an administrator, or through a reset link. */
    PASSWORD_RESET,
    STATUS_CHANGED
}
