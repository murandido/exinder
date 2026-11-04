# R8 rules for the release build.
#
# The libraries the app uses (Compose, Room, Media3, Coil, DataStore) ship their
# own consumer rules, so nothing extra is needed today. Add rules here only for
# code reached through reflection that R8 can't see.
