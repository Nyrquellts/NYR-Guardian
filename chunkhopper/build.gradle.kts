// NYR ChunkHopper sells what it collects through Vault when the buyer's server has it. Vault's API is compiled against
// and put on the test class path only: it never ships in the jar (verifyJar rejects any class outside the plugin's own
// package), and the plugin reaches it only after checking that Vault is installed.
dependencies {
    compileOnly(libs.vault.api) { isTransitive = false }
    testImplementation(libs.vault.api) { isTransitive = false }
}
