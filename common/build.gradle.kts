// :common -- what every NYR Guardian plugin shares. It is shaded into each plugin's jar and relocated under that plugin's own
// package, so two NYR plugins on one server never hand each other their classes.
plugins {
    `java-library`
}

dependencies {
    api(libs.folialib)
}
