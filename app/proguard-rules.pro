# Journaux retirés du build release : aucun appel à android.util.Log ne subsiste.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
    public static *** isLoggable(...);
}
# Bouncy Castle : seule l'API légère Argon2 est utilisée, R8 retire le reste.
-dontwarn org.bouncycastle.**
