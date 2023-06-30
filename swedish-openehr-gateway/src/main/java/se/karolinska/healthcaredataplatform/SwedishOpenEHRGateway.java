package se.karolinska.healthcaredataplatform;

import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.builder.AggregationStrategies;
import org.apache.camel.builder.PredicateBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.http.common.HttpMethods;

import com.jayway.jsonpath.JsonPath;

import net.minidev.json.JSONArray;

public class SwedishOpenEHRGateway extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        final String HSAID_TYPE = "urn:oid:1.2.752.29.4.19";
        final String ORGNUMBER_TYPE = "urn:oid:2.5.4.97";

        final String ORGANIZATION_HSA_HIERARCHY_EXTENSION_URL = "https://commonprofiles.care/StructureDefinition/common-hsaHierarchy";
        final String ORGANIZATION_HSA_HIERARCHY_CAREUNIT_URL = "hsaCareUnit";
        final String ORGANIZATION_HSA_HIERARCHY_CAREPROVIDER_URL = "hsaCareProvider";

        final String JSONPATH_HEALTH_CARE_FACILITY_ID = "$.context.health_care_facility.identifiers[?(@.type == '" + HSAID_TYPE + "')].id";
        final String XPATH_HEALTH_CARE_FACILITY_ID = "/context/health_care_facility/identifiers[@type='" + HSAID_TYPE + "']/id";

        final String JSONPATH_ORGANIZATION_SEARCH_HSA_HIERARCHY_CAREUNIT_URL =
            "$.entry[0].resource.extension[?(@.url == '" +
            ORGANIZATION_HSA_HIERARCHY_EXTENSION_URL +
            "')].extension[?(@.url == '" +
            ORGANIZATION_HSA_HIERARCHY_CAREUNIT_URL +
            "')].valueReference.reference";
        final String ORGANIZATION_SEARCH_HSA_HIERARCHY_CAREPROVIDER_URL_JSONPATH =
            "$.entry[0].resource.extension[?(@.url == '" +
            ORGANIZATION_HSA_HIERARCHY_EXTENSION_URL +
            "')].extension[?(@.url == '" +
            ORGANIZATION_HSA_HIERARCHY_CAREPROVIDER_URL +
            "')].valueReference.reference";


        // Predicate for detecting if this is a "Patient Conditional Create"
        Predicate isCompositionWrite = PredicateBuilder.and(
            header(Exchange.HTTP_PATH).regex("^ehr/.+/composition"),
            header(Exchange.HTTP_METHOD).in(HttpMethods.POST, HttpMethods.PUT)
        );

        // Primary gateway listener (note with compression=true so our route requests/responses can handle both cases)
        from("netty-http:http://0.0.0.0:{{gateway.http.port}}/?matchOnUriPrefix=true&compression=true")
            .log("INCOMING HEADERS=\n${headers}")
            //.log("INCOMING BODY=\n${body}")
            .choice()
                .when(isCompositionWrite)
                    .to("direct:writeComposition")
                .otherwise()
                    .to("direct:toEhr")
                .end()
            .end();

        // Primary gateway target producer
        from("direct:toEhr")
            .log("OUTGOING HEADERS=\n${headers}")
            //.log("OUTGOING BODY=\n${body}")
            // If the call to the target service fails then Camel will return a NettyHttpOperationFailedException stacktrace to the client
            // Instead of this we would rather just return the failure that comes from the target service itself back to the user
            //  (so allow it to fail, and let the failure's body and headers be what is sent back to the client)
            .doTry()
                //.log("TODO turn on actual send to EHR system")
                .toD("netty-http:{{ehr.http.scheme}}://{{ehr.http.hostname}}{{ehr.http.basepath}}/${headers." + Exchange.HTTP_PATH + "}?compression=true")
            .doCatch(Exception.class)
            .end();

        // Inject Organization Cluster for usage supporting PDL
        from("direct:writeComposition")
            .setProperty("hsaidType", constant(HSAID_TYPE))
            .setProperty("orgnumberType", constant(ORGNUMBER_TYPE))
            .choice()
                .when(header(Exchange.CONTENT_TYPE).isEqualTo("application/json"))
                    // TODO here using getJsonPathValue instead of built-in jsonpath due to the value comes with brackets instead of a normal string :( e.g. "[value]" instead of "value"
                    .setProperty("healthCareFacilityId", method(Utils.class, "getJsonPathValue(${body}, \"" + JSONPATH_HEALTH_CARE_FACILITY_ID + "\")"))
                    .to("direct:injectPDLJson")
                //.when(header(Exchange.CONTENT_TYPE).isEqualTo("application/xml"))
                //    .setProperty("healthCareFacilityId", xpath(HEALTH_CARE_FACILITY_ID_XPATH))
                //    .to("direct:injectPDLXML")
                .otherwise()
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpURLConnection.HTTP_BAD_REQUEST))
                    .setBody(simple("{\"error\": \"Content-Type ${headers." + Exchange.CONTENT_TYPE + "} is currently not supported.\"}"))
                    .stop()
                .end()
            .to("direct:toEhr")
            .end();

        AggregationStrategy addOrganizationUrls = new AggregationStrategy() {
            public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
                if (newExchange == null)
                    return oldExchange;
                String response = newExchange.getIn().getBody(String.class);
                if (response != null && !response.isBlank()) {
                    String careUnitUrl = Utils.getJsonPathValue(response, JSONPATH_ORGANIZATION_SEARCH_HSA_HIERARCHY_CAREUNIT_URL);
                    String careProviderUrl = Utils.getJsonPathValue(response, ORGANIZATION_SEARCH_HSA_HIERARCHY_CAREPROVIDER_URL_JSONPATH);
                    String healthCareFacilityName = Utils.getJsonPathValue(response, "$.entry[0].resource.name");
                    if (!careUnitUrl.isBlank() && !careProviderUrl.isBlank() && !healthCareFacilityName.isBlank()) {
                        oldExchange.setProperty("healthCareUnitURL", careUnitUrl);
                        oldExchange.setProperty("healthCareProviderURL", careProviderUrl);
                        oldExchange.setProperty("healthCareFacilityName", healthCareFacilityName);
                    }
                }
                return oldExchange;
            }
        };

        AggregationStrategy addHealthCareUnit = new AggregationStrategy() {
            public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
                if (newExchange == null)
                    return oldExchange;
                String response = newExchange.getIn().getBody(String.class);
                if (response != null && !response.isBlank()) {
                    //TODO add some checks if the FHIR resource is active, is use: official, has right SNOMED code, etc ?
                    String hsaid = Utils.getFhirIdentifierValue(response, HSAID_TYPE);
                    String name = Utils.getJsonPathValue(response, "$.name");
                    if (!hsaid.isBlank() && !name.isBlank()) {
                        oldExchange.setProperty("healthCareUnitId", hsaid);
                        oldExchange.setProperty("healthCareUnitName", name);
                    }
                }
                return oldExchange;
            }
        };

        AggregationStrategy addHealthCareProvider = new AggregationStrategy() {
            public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
                if (newExchange == null)
                    return oldExchange;
                String response = newExchange.getIn().getBody(String.class);
                if (response != null && !response.isBlank()) {
                    //TODO add some checks if the FHIR resource is active, is use: official, has right SNOMED code, etc ?
                    String hsaid = Utils.getFhirIdentifierValue(response, HSAID_TYPE);
                    String name = Utils.getJsonPathValue(response, "$.name");
                    String orgnumber = Utils.getFhirIdentifierValue(response, ORGNUMBER_TYPE);
                    if (!hsaid.isBlank() && !name.isBlank() && !orgnumber.isBlank()) {
                        oldExchange.setProperty("healthCareProviderId", hsaid);
                        oldExchange.setProperty("healthCareProviderName", name);
                        oldExchange.setProperty("healthCareProviderOrgnumber", orgnumber);
                    }
                }
                return oldExchange;
            }
        };

        // Inject for Canonical JSON
        from("direct:injectPDLJson")

            .enrich("direct:getTemplateProperties", AggregationStrategies.flexible()
                .pick(body())
                    .storeInProperty("templateProperties")
            )
            .log("templateProperties via main=${exchangeProperty.templateProperties}")

            .enrich("direct:searchHealthCareFacility", addOrganizationUrls)
            .log("healthCareUnitURL=${exchangeProperty.healthCareUnitURL}")
            .log("healthCareProviderURL=${exchangeProperty.healthCareProviderURL}")
            .log("healthCareFacilityName=${exchangeProperty.healthCareFacilityName}")

            .enrich("direct:getHealthCareUnit", addHealthCareUnit)
            .log("healthCareUnitId=${exchangeProperty.healthCareUnitId}")
            .log("healthCareUnitName=${exchangeProperty.healthCareUnitName}")

            .enrich("direct:getHealthCareProvider", addHealthCareProvider)
            .log("healthCareProviderId=${exchangeProperty.healthCareProviderId}")
            .log("healthCareProviderName=${exchangeProperty.healthCareProviderName}")
            .log("healthCareProviderOrgnumber=${exchangeProperty.healthCareProviderOrgnumber}")

            .setProperty("orgClusterId", method(Utils.class, "getJsonPathValue(${exchangeProperty.templateProperties}, \"$.organization_cluster_archetype_id\")"))
            .toD("jslt:transform-Composition-inject-${exchangeProperty.orgClusterId}.json?allowContextMapAll=true")
            .log("Inject Complete")
            .end();

        from("direct:searchHealthCareFacility")
            .removeHeaders("*")
            .setHeader(Exchange.HTTP_METHOD, constant(HttpMethods.POST))
            .setHeader(Exchange.HTTP_PATH, constant("Organization/_search"))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/x-www-form-urlencoded"))
            .setBody(simple("identifier=" + HSAID_TYPE + "|${exchangeProperty.healthCareFacilityId}"))
            .to("direct:toFhir")
            .end();


        from("direct:getHealthCareUnit")
            .removeHeaders("*")
            .setHeader(Exchange.HTTP_METHOD, constant(HttpMethods.GET))
            .setHeader(Exchange.HTTP_PATH, exchangeProperty("healthCareUnitURL"))
            .setBody(constant(""))
            .to("direct:toFhir")
            .end();

        from("direct:getHealthCareProvider")
            .removeHeaders("*")
            .setHeader(Exchange.HTTP_METHOD, constant(HttpMethods.GET))
            .setHeader(Exchange.HTTP_PATH, exchangeProperty("healthCareProviderURL"))
            .setBody(constant(""))
            .to("direct:toFhir")
            .end();

        // Execute a request against the FHIR service
        from("direct:toFhir")
            .log("TO FHIR Body=\n${body}")
            .log("TO FHIR Headers=\n${headers}")
            .toD("netty-http:{{fhir.http.scheme}}://{{fhir.http.hostname}}{{fhir.http.basepath}}/${headers." + Exchange.HTTP_PATH + "}?compression=false&disconnect=true") // -1 cacheSize to turn off
            .setBody(simple("${body}", String.class)) // read non-cached stream into body once
            .log("Response from FHIR=${body}")
            .end();
        
        // Get properties of a Composition's template in a JSON block via XSLT
        from("direct:getTemplateProperties")
            .to("direct:getTemplate1.4")
            .to("xslt:template-OrgClusterPropertiesJson.xslt")
            .log("Result of XSLT=\n${body}")
            .end();

        // Get a Composition's template in XML
        from("direct:getTemplate1.4")
            .removeHeaders("*")
            .setHeader(Exchange.HTTP_METHOD, constant(HttpMethods.GET))
            // TODO: getting the template_id should be dynamic based on content-type ? JSON, Flat JSON, XML, etc ?
            .setHeader(Exchange.HTTP_PATH, jsonpath("$.archetype_details.template_id.value"))
            .setHeader(Exchange.HTTP_PATH, simple("definition/template/adl1.4/${headers." + Exchange.HTTP_PATH + "}"))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/xml"))
            .setHeader("Accept", constant("application/xml"))
            .setBody(constant(""))
            .to("direct:toEhr")
            .end();

    }

    protected final static class Utils {

        /**
         * Helper method to create/update maps in an easy way including within routes using the method() Expression clause
         * @param map
         * @param key
         * @param value
         * @return map including a new/updated entry for key=value
         */
        static Map<Object, Object> putInMap(Map<Object, Object> map, Object key, Object value) {
            if (map == null)
                map = new HashMap<>();
            map.put(key, value);
            return map;
        }

        static String extractPattern(String source, String matchPattern) {
            Pattern pattern = Pattern.compile(matchPattern);
            Matcher matcher = pattern.matcher(source);
            if (matcher.find())
                return matcher.group(1);
            else
                return "";
        }

        static boolean doesFhirIdentifierExist(String resourceJson, String identifierSystem) {
            try {
                return getFhirIdentifierValue(resourceJson, identifierSystem).isBlank();
            }
            catch (Exception e) {
                // if anything fails at any time, then it is "not a match"; return false
                return false;
            }
        }

        static String getFhirIdentifierValue(String resourceJson, String identifierSystem) {
            return getJsonPathValue(resourceJson, "$.identifier[?(@.system == '" + identifierSystem + "')].value");
        }

        static String getJsonPathValue(String jsonBody, String jsonPath) {
            Object result = JsonPath.read(jsonBody, jsonPath);
            if (result instanceof JSONArray) {
                JSONArray json = (JSONArray) result;
                if (!json.isEmpty())
                    return json.get(0).toString();
                else
                    return ""; //TODO throw exception or allow empty/null?
            } else if(result instanceof String) {
                return (String) result;
            } else {
                return ""; //TODO throw exception or allow empty/null?
            }
        }

    }


}
