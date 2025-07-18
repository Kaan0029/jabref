package org.jabref.logic.quality.consistency;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.jabref.logic.bibtex.comparator.BibEntryByCitationKeyComparator;
import org.jabref.logic.bibtex.comparator.BibEntryByFieldsComparator;
import org.jabref.logic.bibtex.comparator.FieldComparatorStack;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.SpecialField;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.field.UserSpecificCommentField;
import org.jabref.model.entry.types.EntryType;

public class BibliographyConsistencyCheck {

    private static final Set<Field> EXPLICITLY_EXCLUDED_FIELDS = Set.of(
                            StandardField.COMMENT,
                            StandardField.CROSSREF,
                            StandardField.GROUPS,
                            StandardField.CITES,
                            StandardField.PDF,
                            StandardField.REVIEW,
                            StandardField.SORTKEY,
                            StandardField.SORTNAME,
                            StandardField.TYPE,
                            StandardField.XREF
                    );

    private static Set<Field> filterExcludedFields(Collection<Field> fields) {
        return fields.stream()
                     .filter(field -> !EXPLICITLY_EXCLUDED_FIELDS.contains(field))
                     .filter(field -> !StandardField.AUTOMATIC_FIELDS.contains(field))
                     .filter(field -> !(field instanceof SpecialField))
                     .filter(field -> !(field instanceof UserSpecificCommentField))
                     .collect(Collectors.toSet());
    }

    public record Result(Map<EntryType, EntryTypeResult> entryTypeToResultMap) {
    }

    public record EntryTypeResult(Collection<Field> fields, SequencedCollection<BibEntry> sortedEntries) {
    }

    /**
     * Checks the consistency of the given entries by looking at the present and absent fields.
     * <p>
     * Computation takes place grouped by each entryType.
     * Computes the fields set in all entries. In case entries of the same type has more fields defined, it is output.
     * <p>
     * This class <em>does not</em> check whether all required fields are present or if the fields are valid for the entry type.
     * That result can a) be retrieved by using the JabRef UI and b) by checking the CSV output of {@link BibliographyConsistencyCheckResultCsvWriter#writeFindings}
     *
     * @implNote This class does not implement {@link org.jabref.logic.integrity.DatabaseChecker}, because it returns a list of {@link org.jabref.logic.integrity.IntegrityMessage}, which are too fine-grained.
     */
    public Result check(List<BibEntry> entries, BiConsumer<Integer, Integer> entriesGroupingProgress) {
        // collects fields existing in any entry, scoped by entry type
        Map<EntryType, Set<Field>> entryTypeToFieldsInAnyEntryMap = new HashMap<>();
        // collects fields existing in all entries, scoped by entry type
        Map<EntryType, Set<Field>> entryTypeToFieldsInAllEntriesMap = new HashMap<>();
        // collects entries of the same type
        Map<EntryType, Set<BibEntry>> entryTypeToEntriesMap = new HashMap<>();

        collectEntriesIntoMaps(entries, entryTypeToFieldsInAnyEntryMap, entryTypeToFieldsInAllEntriesMap, entryTypeToEntriesMap);

        Map<EntryType, EntryTypeResult> resultMap = new HashMap<>();

        int counter = 0;
        for (Map.Entry<EntryType, Set<Field>> mapEntry : entryTypeToFieldsInAnyEntryMap.entrySet()) {
            entriesGroupingProgress.accept(counter++, entryTypeToFieldsInAnyEntryMap.size());
            EntryType entryType = mapEntry.getKey();
            Set<Field> fields = mapEntry.getValue();
            Set<Field> commonFields = entryTypeToFieldsInAllEntriesMap.get(entryType);
            assert commonFields != null;
            Set<Field> uniqueFields = new HashSet<>(fields);
            uniqueFields.removeAll(commonFields);

            if (uniqueFields.isEmpty()) {
                continue;
            }

            List<Comparator<BibEntry>> comparators = List.of(
                    new BibEntryByCitationKeyComparator(),
                    new BibEntryByFieldsComparator());
            FieldComparatorStack<BibEntry> comparatorStack = new FieldComparatorStack<>(comparators);

            List<BibEntry> differingEntries = entryTypeToEntriesMap
                    .get(entryType).stream()
                    .filter(entry -> !filterExcludedFields(entry.getFields()).equals(commonFields))
                    .sorted(comparatorStack)
                    .toList();

            resultMap.put(entryType, new EntryTypeResult(uniqueFields, differingEntries));
        }

        return new Result(resultMap);
    }

    private static void collectEntriesIntoMaps(List<BibEntry> entries, Map<EntryType, Set<Field>> entryTypeToFieldsInAnyEntryMap, Map<EntryType, Set<Field>> entryTypeToFieldsInAllEntriesMap, Map<EntryType, Set<BibEntry>> entryTypeToEntriesMap) {
        for (BibEntry entry : entries) {
            EntryType entryType = entry.getType();

            entryTypeToFieldsInAnyEntryMap
                    .computeIfAbsent(entryType, _ -> new HashSet<>())
                    .addAll(filterExcludedFields(entry.getFields()));

            entryTypeToFieldsInAllEntriesMap
                    .computeIfAbsent(entryType, _ -> new HashSet<>(filterExcludedFields(entry.getFields())))
                    .retainAll(filterExcludedFields(entry.getFields()));

            entryTypeToEntriesMap
                    .computeIfAbsent(entryType, _ -> new HashSet<>())
                    .add(entry);
        }
    }
}
