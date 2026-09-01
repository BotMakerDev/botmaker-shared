package com.botmaker.shared.project;

import java.util.Locale;

/**
 * The bijection between an image template's file name and the constant the generated {@code Templates} class
 * names it by: the file is {@code ore.png} and the constant is {@code ORE}.
 *
 * <h2>Why a constant and not the path</h2>
 *
 * <p>A template used to be spelled {@code new ImageTemplate("src/main/resources/images/ore.png")} — a path
 * repeated at every use site, invisible to the compiler, and wrong in every one of them the moment the file
 * is renamed. {@code Templates.ORE} is the same string declared once, so a rename regenerates one line and
 * breaks the build at each site that has to change, rather than leaving a bot that compiles and finds
 * nothing.
 *
 * <h2>Why the mapping has to be exact</h2>
 *
 * <p>A constant is only useful if it can be read back: an editor rendering a picker chip has
 * {@code Templates.ORE} in the AST and needs the file it stands for. A side table would be one more thing
 * that can disagree with the images folder, so the two names are a bijection instead — uppercasing a
 * lowercase identifier is reversible and cannot collide.
 *
 * <p>A name that predates that rule (mixed case, or a {@code -}) simply has no constant: {@link #constantFor}
 * answers null, the generated class skips it, and its path stays spelled out at the use site. Both spellings
 * are read, so the two kinds coexist in one project and an old bot keeps compiling. Sanitising the awkward
 * names instead would reintroduce exactly the side table this avoids — {@code Gold-Ore} and {@code Gold_Ore}
 * both want {@code GOLD_ORE}, so one gets a suffix and the constant stops saying which file it means.
 *
 * <h2>Why it is in botmaker-shared</h2>
 *
 * <p>It was {@code com.botmaker.sdk.authoring.TemplateNames} until 2026-09-01, and it moved for the reason
 * this module exists: <b>two repositories need the same answer and must not each hold a copy of it.</b> The
 * SDK's picture library writes these files; the host's editor reads {@code Templates.ORE} out of a bot's
 * syntax tree to draw a chip, and rewrites every use of it when a picture is renamed. A second copy of the
 * uppercase rule would answer the rename wrongly the day anyone adjusted the other.
 *
 * <p>It could not stay in the SDK because the host is on its way <em>off</em> the SDK — a host must be able
 * to read a bot's own file conventions without depending on the plugin that established them — and it did
 * not need to become plugin contract, because nothing here is a capability a host grants: it is arithmetic
 * over two strings. {@code botmaker-shared} is published, so the plugin resolves it exactly as the host does.
 */
public final class TemplateNames {

    /** The generated class's simple name, in the project's base package next to {@code Parameters}. */
    public static final String CLASS_NAME = "Templates";

    /**
     * Where a project keeps its pictures, project-relative, with a trailing slash.
     *
     * <p>Here rather than in the SDK's {@code WireText} because the two names below are built from it, and a
     * prefix that lived on the far side of a module boundary would make this class unmovable. {@code WireText}
     * keeps its own {@code IMAGE_PREFIX} as the wire format's spelling of the same folder, delegating here.
     */
    public static final String IMAGE_PREFIX = "src/main/resources/images/";

    /**
     * The placeholder template every project is created with, so a freshly dropped vision block points at a
     * file that is really there instead of at one the user has not captured yet.
     */
    public static final String DEFAULT_TEMPLATE_NAME = "default_template";

    /** The placeholder's file name, {@code default_template.png}. */
    public static final String DEFAULT_TEMPLATE_FILE = DEFAULT_TEMPLATE_NAME + ".png";

    private TemplateNames() {}

    /**
     * The placeholder's pixels: a 32px teal/white checker, generated rather than shipped as an asset.
     *
     * <p>It is here, beside the names, because two questions need the same answer and must not each hold
     * their own copy of the pattern: <em>what does a new project's placeholder look like</em> (creation) and
     * <em>is this file still the untouched placeholder</em> (an editor deciding whether to put it in an
     * export). A second checker written elsewhere would answer the second question wrongly the day anyone
     * adjusted the first.
     */
    public static java.awt.image.BufferedImage defaultTemplateImage() {
        int size = 32;
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                img.setRGB(x, y, ((x / 8) + (y / 8)) % 2 == 0 ? 0xFF1ABC9C : 0xFFECF0F1);
            }
        }
        return img;
    }

    /**
     * The constant naming the template called {@code baseName}, or {@code null} when that name cannot be one
     * — anything that is not a lowercase ASCII Java identifier.
     */
    public static String constantFor(String baseName) {
        if (baseName == null || baseName.isBlank()) return null;
        if (!baseName.equals(baseName.toLowerCase(Locale.ROOT))) return null;
        if (!Character.isJavaIdentifierStart(baseName.charAt(0)) || baseName.charAt(0) == '$') return null;
        for (int i = 1; i < baseName.length(); i++) {
            char c = baseName.charAt(i);
            if (c != '_' && !Character.isLetterOrDigit(c)) return null;
            if (c > 127) return null;   // an identifier Java accepts but a constant nobody wants to read
        }
        return baseName.toUpperCase(Locale.ROOT);
    }

    /** The template file name a constant stands for, or {@code null} when it is not one of ours. */
    public static String baseNameFor(String constant) {
        if (constant == null || constant.isBlank()) return null;
        String lower = constant.toLowerCase(Locale.ROOT);
        return constant.equals(constantFor(lower)) ? lower : null;
    }

    /** The project-relative path a constant stands for, or {@code null} when it is not one of ours. */
    public static String pathForConstant(String constant) {
        String baseName = baseNameFor(constant);
        return baseName == null ? null : IMAGE_PREFIX + baseName + ".png";
    }

    /** The constant for a project-relative template path, or {@code null} when that path has none. */
    public static String constantForPath(String path) {
        if (path == null || !path.startsWith(IMAGE_PREFIX) || !path.endsWith(".png")) return null;
        return constantFor(path.substring(IMAGE_PREFIX.length(), path.length() - ".png".length()));
    }
}
