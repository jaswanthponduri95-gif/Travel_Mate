package com.travelmate.model;

/** Public view of a user account (never carries the password hash). */
public class User implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    public int id;
    public String fullName;
    public String email;
    /** True when this account may open the AI Settings page (see AiAccess). Computed, never stored. */
    public boolean aiAccess;

    public User(int id, String fullName, String email) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
    }
}
