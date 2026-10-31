/* Cricket 07 profile for the Android build (see host/profile.h for what each field means).
 * The game only needs its own folder; if a log (RC_LOG=1 in files/env.txt) shows it opening a
 * registry key that isn't here, add the value to PROFILE_REGISTRY. */
#ifndef RC_PROFILE_H
#define RC_PROFILE_H

#define PROFILE_EXE        "cricket07.exe"
#define PROFILE_GUEST_ROOT "C:\\Program Files\\EA SPORTS\\Cricket 07"
#define PROFILE_GUEST_EXE  "cricket07.exe"
#define PROFILE_TITLE      "Cricket 07"

#define PROFILE_REGISTRY \
    { "SOFTWARE\\EA SPORTS\\Cricket 07", "Install Dir", 1, PROFILE_GUEST_ROOT, 0 },

#endif
