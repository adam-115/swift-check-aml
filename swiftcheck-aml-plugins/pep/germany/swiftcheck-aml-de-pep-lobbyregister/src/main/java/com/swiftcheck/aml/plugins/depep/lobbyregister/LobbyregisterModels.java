package com.swiftcheck.aml.plugins.depep.lobbyregister;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Modeles DTO representant les structures JSON de l'API Lobbyregister du Deutscher Bundestag (/sucheDetailJson).
 */
public final class LobbyregisterModels {

    private LobbyregisterModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DetailedSearchResults {
        public String source;
        public String sourceUrl;
        public String searchUrl;
        public String searchDate;
        public int resultCount;
        public List<SearchResult> results = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchResult {
        public String registerNumber;
        public RegisterEntryDetails registerEntryDetails;
        public AccountDetails accountDetails;
        public LobbyistIdentity lobbyistIdentity;
        public List<ClientOrganization> clientOrganizations = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RegisterEntryDetails {
        public long registerEntryId;
        public int version;
        public String detailsPageUrl;
        public String pdfUrl;
        public String validFromDate;
        public String validUntilDate;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccountDetails {
        public boolean activeLobbyist = true;
        public String firstPublicationDate;
        public String lastUpdateDate;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LobbyistIdentity {
        public String identity; // "ORGANIZATION" ou "NATURAL"
        public String name;
        public CodeLabel legalFormType;
        public CodeLabel legalForm;
        public List<PersonEntry> legalRepresentatives = new ArrayList<>();
        @JsonProperty("entrustedPersons")
        public List<PersonEntry> entrustedPersons = new ArrayList<>();
        @JsonProperty("namedEmployees")
        public List<PersonEntry> namedEmployees = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CodeLabel {
        public String code;
        public String de;
        public String en;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PersonEntry {
        public String academicDegreeBefore;
        public String firstName;
        public String lastName;
        public String academicDegreeAfter;
        public String function;
        public boolean recentGovernmentFunctionPresent;
        public RecentGovernmentFunction recentGovernmentFunction;

        public String fullName() {
            StringBuilder sb = new StringBuilder();
            if (academicDegreeBefore != null && !academicDegreeBefore.isBlank()) {
                sb.append(academicDegreeBefore.trim()).append(" ");
            }
            if (firstName != null && !firstName.isBlank()) {
                sb.append(firstName.trim()).append(" ");
            }
            if (lastName != null && !lastName.isBlank()) {
                sb.append(lastName.trim());
            }
            if (academicDegreeAfter != null && !academicDegreeAfter.isBlank()) {
                sb.append(" ").append(academicDegreeAfter.trim());
            }
            return sb.toString().trim();
        }

        public String normalizedName() {
            String name = fullName().toLowerCase();
            return name.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                    .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RecentGovernmentFunction {
        public boolean ended;
        public String endDate;
        public CodeLabel type;
        public FederalAdministration federalAdministration;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FederalAdministration {
        public String supremeFederalAuthority;
        public String supremeFederalAuthorityShort;
        public int supremeFederalAuthorityElectionPeriod;
        public String function;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClientOrganization {
        public String name;
        public CodeLabel legalForm;
        public List<PersonEntry> legalRepresentatives = new ArrayList<>();
    }
}

