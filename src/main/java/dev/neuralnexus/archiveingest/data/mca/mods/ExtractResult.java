package dev.neuralnexus.archiveingest.data.mca.mods;

import dev.neuralnexus.archiveingest.data.mca.Link;

import java.util.Collection;

public record ExtractResult(ModVersion mod, Collection<Link> links) {}
