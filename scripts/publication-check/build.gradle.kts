import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Usage

val forkGroup = providers.gradleProperty("forkPublicationGroup").get()
val forkVersion = providers.gradleProperty("forkPublicationVersion").get()
repositories {
  maven { url = uri(providers.gradleProperty("forkRepository").get()) }
  google()
  mavenCentral()
}
val sdk by configurations.creating {
  attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}
dependencies.add(sdk.name, "$forkGroup:facebook-android-sdk:$forkVersion")
tasks.register("verifyForkGraph") {
  doLast {
    val expected = setOf("facebook-android-sdk", "facebook-core", "facebook-common", "facebook-bolts", "facebook-login", "facebook-share", "facebook-applinks", "facebook-messenger", "facebook-gamingservices")
    val components = sdk.incoming.resolutionResult.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
    val patched = components.filter { it.module in expected }
    check(patched.map { it.module }.toSet() == expected) { "Incomplete resolved SDK graph: $patched" }
    patched.forEach { check(it.group == forkGroup && it.version == forkVersion) { "Official or mismatched SDK module resolved: $it" } }
    val artifacts = sdk.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.name in expected }
    check(artifacts.size == expected.size && artifacts.all { it.type == "aar" && it.file.length() > 0 }) { "Incomplete resolved AARs: $artifacts" }
    println("Resolved ${patched.size} fork SDK modules exclusively at $forkGroup:$forkVersion")
  }
}
