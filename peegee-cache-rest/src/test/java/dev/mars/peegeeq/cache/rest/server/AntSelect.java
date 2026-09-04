package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.List;

/**
 * Helpers for Ant Design {@code Select} controls (U11.4/U11.5). An antd Select is a
 * {@code combobox} whose options render in a body-level listbox only while it is open, so
 * Playwright's native {@code selectOption} does not apply. The console renders every option
 * and the current selection through {@code ValueSelect}, which stamps the server value on a
 * {@code data-value} attribute, so scenarios address options by the reviewed server value
 * exactly as they did with native {@code <select>} elements; labels remain available for
 * scenarios whose expected result is the visible wording.
 */
final class AntSelect {

    private static final String OPEN_DROPDOWN = ".ant-select-dropdown:not(.ant-select-dropdown-hidden)";
    private static final String CLOSEST_SELECT =
            "xpath=ancestor::*[contains(concat(' ', normalize-space(@class), ' '), ' ant-select ')][1]";

    private AntSelect() {
    }

    /** Opens the select addressed by {@code combobox} and chooses the option carrying server {@code value}. */
    static void choose(Page page, Locator combobox, String value) {
        open(combobox);
        page.locator(OPEN_DROPDOWN + " [role='option'] [data-value='" + value + "']").click();
    }

    /** Opens the select and returns the option server values in display order, then closes it. */
    static List<String> values(Page page, Locator combobox) {
        open(combobox);
        Locator options = page.locator(OPEN_DROPDOWN + " [role='option'] [data-value]");
        options.first().waitFor();
        List<String> values = options.all().stream().map(option -> option.getAttribute("data-value")).toList();
        page.keyboard().press("Escape");
        return values;
    }

    /** Opens the select and returns the visible option labels in display order, then closes it. */
    static List<String> optionLabels(Page page, Locator combobox) {
        open(combobox);
        Locator options = page.locator(OPEN_DROPDOWN + " [role='option']");
        options.first().waitFor();
        List<String> labels = options.allInnerTexts();
        page.keyboard().press("Escape");
        return labels;
    }

    /**
     * The rendered current selection of the select addressed by {@code combobox}; assert its
     * {@code data-value} attribute to check the selected server value.
     */
    static Locator selection(Locator combobox) {
        return combobox.locator(CLOSEST_SELECT).locator(".ant-select-selection-item [data-value]");
    }

    private static void open(Locator combobox) {
        combobox.locator(CLOSEST_SELECT).locator(".ant-select-selector").click();
    }
}
