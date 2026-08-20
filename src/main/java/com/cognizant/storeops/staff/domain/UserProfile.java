package com.cognizant.storeops.staff.domain;

/**
 * Contact and shift detail for a staff member.
 *
 * @param phone        contact number
 * @param department   department the staff member works in, e.g. GROCERY
 * @param shiftPattern shift label, e.g. EARLY, LATE, NIGHT
 */
public record UserProfile(String phone, String department, String shiftPattern) {

    public static UserProfile empty() {
        return new UserProfile(null, null, null);
    }
}
