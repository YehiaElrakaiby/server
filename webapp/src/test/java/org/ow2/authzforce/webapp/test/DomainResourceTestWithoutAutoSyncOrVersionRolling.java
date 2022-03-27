/*
 * Copyright (C) 2012-2022 THALES.
 *
 * This file is part of AuthzForce CE.
 *
 * AuthzForce CE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AuthzForce CE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AuthzForce CE.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.ow2.authzforce.webapp.test;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.*;
import org.apache.cxf.jaxrs.client.ClientConfiguration;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.jaxrs.provider.BinaryDataProvider;
import org.json.JSONObject;
import org.ow2.authzforce.core.pdp.impl.io.MultiDecisionXacmlJaxbRequestPreprocessor;
import org.ow2.authzforce.core.pdp.impl.io.SingleDecisionXacmlJaxbRequestPreprocessor;
import org.ow2.authzforce.core.pdp.testutil.ext.TestCombinedDecisionXacmlJaxbResultPostprocessor;
import org.ow2.authzforce.core.pdp.testutil.ext.TestDnsNameValueEqualFunction;
import org.ow2.authzforce.core.pdp.testutil.ext.TestDnsNameWithPortValue;
import org.ow2.authzforce.core.pdp.testutil.ext.TestOnPermitApplySecondCombiningAlg;
import org.ow2.authzforce.core.pdp.testutil.ext.xmlns.TestAttributeProviderDescriptor;
import org.ow2.authzforce.core.xmlns.pdp.InOutProcChain;
import org.ow2.authzforce.pap.dao.flatfile.FlatFileBasedDomainsDao;
import org.ow2.authzforce.pap.dao.flatfile.FlatFileBasedDomainsDao.PdpCoreFeature;
import org.ow2.authzforce.pap.dao.flatfile.FlatFileBasedDomainsDao.PdpFeatureType;
import org.ow2.authzforce.pap.dao.flatfile.FlatFileDAOUtils;
import org.ow2.authzforce.rest.api.jaxrs.*;
import org.ow2.authzforce.rest.api.xmlns.*;
import org.ow2.authzforce.webapp.JsonRiCxfJaxrsProvider;
import org.ow2.authzforce.xacml.identifiers.XPathVersion;
import org.ow2.authzforce.xacml.identifiers.XacmlAttributeCategory;
import org.ow2.authzforce.xmlns.pdp.ext.AbstractAttributeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.*;
import org.w3._2005.atom.Link;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import java.io.File;
import java.io.FileFilter;
import java.util.*;
import java.util.stream.Collectors;

import static org.testng.Assert.*;

/**
 * Main tests specific to a domain, requires {@link RootResourcesTest} to be run first
 */
public class DomainResourceTestWithoutAutoSyncOrVersionRolling extends RestServiceTest
{
	private static final Logger LOGGER = LoggerFactory.getLogger(DomainResourceTestWithoutAutoSyncOrVersionRolling.class);

	private static final FileFilter DIRECTORY_FILTER = File::isDirectory;

	private WebClient httpClient;
	private boolean enableFastInfoset = false;

	private DomainAPIHelper testDomainHelper = null;

	private DomainResource testDomain = null;
	private String testDomainId = null;

	private String testDomainExternalId = "test";

	/**
	 * Test parameters from testng.xml are ignored when executing with maven surefire plugin, so we use default values for all.
	 * 
	 * WARNING: the BeforeTest-annotated method must be in the test class, not in a super class although the same method logic is used in other test class
	 * 
	 * @param remoteAppBaseUrl remote app's base URL
	 * @param enableFastInfoset enable Fast Infoset
	 * @param domainSyncIntervalSec domain synchronization interval in seconds
	 * @throws Exception error
	 */
	@Parameters({ "remote.base.url", "enableFastInfoset", "useJSON", "enableDoSMitigation", "org.ow2.authzforce.domains.sync.interval", "enablePdpOnly" })
	@BeforeTest()
	public void beforeTest(@Optional final String remoteAppBaseUrl, @Optional("false") final boolean enableFastInfoset, @Optional("false") final boolean useJSON,
	        @Optional("true") final boolean enableDoSMitigation, @Optional("-1") final int domainSyncIntervalSec, @Optional("false") final Boolean enablePdpOnly) throws Exception
	{
		startServerAndInitCLient(remoteAppBaseUrl, useJSON ? ClientType.JSON : (enableFastInfoset ? ClientType.FAST_INFOSET : ClientType.XML), "", enableDoSMitigation, domainSyncIntervalSec,
		        enablePdpOnly);
	}

	/**
	 * 
	 * WARNING: the AfterTest-annotated method must be in the test class, not in a super class although the same method logic is used in other test class
	 *
	 * @throws Exception error
	 */
	@AfterTest
	public void afterTest() throws Exception
	{
		shutdownServer();
	}

	/**
	 * @param remoteAppBaseUrl remote app's base URL
	 * @param enableFastInfoset enable Fast Infoset
	 * @throws Exception error
	 * 
	 *             NB: use Boolean class instead of boolean primitive type for Testng parameter, else the default value in @Optional annotation is not handled properly.
	 */
	@Parameters({ "remote.base.url", "enableFastInfoset", "enablePdpOnly" })
	@BeforeClass
	public void addDomain(@Optional final String remoteAppBaseUrl, @Optional("false") final Boolean enableFastInfoset, @Optional("false") final Boolean enablePdpOnly) throws Exception
	{
		this.enableFastInfoset = enableFastInfoset;
		if (enablePdpOnly)
		{
			/*
			 * enablePdpOnly=true does not allow adding domain via REST API, so we add it on the filesystem directly
			 */
			FlatFileDAOUtils.copyDirectory(SAMPLE_DOMAIN_DIR, SAMPLE_DOMAIN_COPY_DIR, 3);
			testDomainId = SAMPLE_DOMAIN_ID;
		}
		else
		{
			final Link domainLink = domainsAPIProxyClient.addDomain(new DomainProperties("Some description", testDomainExternalId));
			assertNotNull(domainLink, "Domain creation failure");

			// The link href gives the new domain ID
			testDomainId = domainLink.getHref();
		}

		LOGGER.debug("Added domain ID={}", testDomainId);
		testDomain = domainsAPIProxyClient.getDomainResource(testDomainId);
		assertNotNull(testDomain, String.format("Error retrieving domain ID=%s", testDomainId));
		this.testDomainHelper = new DomainAPIHelper(testDomainId, testDomain, unmarshaller, pdpModelHandler);

		final ClientConfiguration apiProxyClientConf = WebClient.getConfig(domainsAPIProxyClient);
		final String appBaseUrl = apiProxyClientConf.getEndpoint().getEndpointInfo().getAddress();

		if(enableFastInfoset)
		{
			// FastInfoset payloads will be passed to the client as byte[] (binary)
			httpClient = WebClient.create(appBaseUrl, Collections.singletonList(new BinaryDataProvider()), true);
		} else {
			httpClient = WebClient.create(appBaseUrl, Collections.singletonList(new JsonRiCxfJaxrsProvider<JSONObject>()), true);
		}

		assertNotNull(testDomain, String.format("Error retrieving domain ID=%s", testDomainId));
	}

	@Parameters({ "enablePdpOnly" })
	@AfterClass
	/*
	 * deleteDomain() already tested in {@link DomainSetTest#deleteDomains()}, so this is just for cleaning after testing
	 */
	public void deleteDomain(@Optional("false") final Boolean enablePdpOnly) throws Exception
	{
		if (enablePdpOnly)
		{

			/*
			 * enablePdpOnly=true does not allow deleting domain via REST API, so we delete it on the filesystem directly
			 */
			final File deleteSrcDir = new File(DOMAINS_DIR, this.testDomainId);
			FlatFileDAOUtils.deleteDirectory(deleteSrcDir.toPath(), 3);
		}
		else
		{
			assertNotNull(testDomain.deleteDomain(), String.format("Error deleting domain ID=%s", testDomainId));
		}
	}

	@Parameters({ "enablePdpOnly" })
	@Test
	public void getDomain(@Optional("false") final Boolean enablePdpOnly)
	{
		final Domain domainResourceInfo;
		try
		{
			domainResourceInfo = testDomain.getDomain();
			assertFalse(enablePdpOnly, "getDomain method allowed but enablePdpOnly=true");
		}
		catch (final ServerErrorException e)
		{
			assertTrue(enablePdpOnly, "getDomain method not allowed but enablePdpOnly=false");
			return;
		}

		assertNotNull(domainResourceInfo);

		final DomainProperties props = domainResourceInfo.getProperties();
		assertNotNull(props);
	}

	@Parameters({ "enablePdpOnly" })
	@Test
	public void getDomainProperties(@Optional("false") final Boolean enablePdpOnly)
	{
		final DomainPropertiesResource propsResource = testDomain.getDomainPropertiesResource();
		assertNotNull(propsResource);
		final DomainProperties props;
		try
		{
			props = propsResource.getDomainProperties();
			assertFalse(enablePdpOnly, "getDomainProperties method allowed but enablePdpOnly=true");
		}
		catch (final ServerErrorException e)
		{
			assertTrue(enablePdpOnly, "getDomainProperties method not allowed but enablePdpOnly=false");
			return;
		}

		assertNotNull(props);
	}

	@Parameters({ "enablePdpOnly" })
	@Test(dependsOnMethods = { "getDomainProperties" })
	public void updateDomainProperties(@Optional("false") final Boolean enablePdpOnly)
	{
		final DomainPropertiesResource propsResource = testDomain.getDomainPropertiesResource();
		assertNotNull(propsResource);

		final byte[] randomBytes = new byte[128];
		RestServiceTest.PRNG.nextBytes(randomBytes);
		final String description = FlatFileDAOUtils.base64UrlEncode(randomBytes);
		final byte[] randomBytes2 = new byte[16];
		RestServiceTest.PRNG.nextBytes(randomBytes2);
		// externalId must be a xs:NCName, therefore cannot start with a number
		final String newExternalId = "external" + FlatFileDAOUtils.base64UrlEncode(randomBytes2);

		try
		{
			propsResource.updateDomainProperties(new DomainProperties(description, newExternalId));
			assertFalse(enablePdpOnly, "updateDomainProperties method allowed but enablePdpOnly=true");
		}
		catch (final ServerErrorException e)
		{
			assertTrue(enablePdpOnly, "updateDomainProperties method not allowed but enablePdpOnly=false");
			return;
		}

		// verify result
		final DomainProperties newProps = testDomain.getDomainPropertiesResource().getDomainProperties();
		assertEquals(newProps.getDescription(), description);
		assertEquals(newProps.getExternalId(), newExternalId);

		// test old externalID -> should fail
		final List<Link> domainLinks = domainsAPIProxyClient.getDomains(testDomainExternalId).getLinks();
		assertTrue(domainLinks.isEmpty(),
		        "Update of externalId on GET /domains/" + this.testDomainId + "/properties failed: old externalId '" + testDomainExternalId + "' still mapped to the domain");

		testDomainExternalId = newExternalId;

		// test the new externalId
		final List<Link> domainLinks2 = domainsAPIProxyClient.getDomains(newExternalId).getLinks();
		final String matchedDomainId = domainLinks2.get(0).getHref();
		assertEquals(matchedDomainId, testDomainId, "Update of externalId on GET /domains/" + this.testDomainId + "/properties failed: getDomains(externalId = " + newExternalId
		        + ") returned wrong domainId: " + matchedDomainId + " instead of " + testDomainId);
	}

	private void verifySyncAfterDomainPropertiesFileModification(final String newExternalId)
	{
		// test the old externalId
		final List<Link> domainLinks = domainsAPIProxyClient.getDomains(testDomainExternalId).getLinks();
		assertTrue(domainLinks.isEmpty(), "Manual sync of externalId with GET or HEAD /domains/" + this.testDomainId + "/properties failed: old externalId still mapped to the domain:");

		testDomainExternalId = newExternalId;

		// test the new externalId
		// test externalId
		final List<Link> domainLinks2 = domainsAPIProxyClient.getDomains(newExternalId).getLinks();
		final String matchedDomainId = domainLinks2.get(0).getHref();
		assertEquals(matchedDomainId, testDomainId, "Manual sync of externalId with GET or HEAD /domains/" + this.testDomainId + "/properties failed: getDomains(externalId = " + newExternalId
		        + ") returned wrong domainId: " + matchedDomainId + " instead of " + testDomainId);
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "getDomainProperties" })
	public void headDomainPropertiesAfterFileModification(@Optional final String remoteAppBaseUrl, @Optional("false") final Boolean isFilesystemLegacy) throws InterruptedException, JAXBException
	{

		// skip test if server not started locally
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final String newExternalId = testDomainExternalId + "ter";
		testDomainHelper.modifyDomainPropertiesFile(newExternalId, isFilesystemLegacy);

		// manual sync with HEAD /domains/{id}/properties
		final javax.ws.rs.core.Response response = httpClient.reset().path("domains").path(testDomainId).path("properties").accept(MediaType.APPLICATION_XML).head();
		assertEquals(response.getStatus(), javax.ws.rs.core.Response.Status.OK.getStatusCode(), "HEAD /domains/" + testDomainId + "/properties failed");

		verifySyncAfterDomainPropertiesFileModification(newExternalId);

	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "getDomainProperties" })
	public void getDomainPropertiesAfterFileModification(@Optional final String remoteAppBaseUrl, @Optional("false") final Boolean isFilesystemLegacy) throws JAXBException, InterruptedException
	{
		// skip test if server not started locally
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final String newExternalId = testDomainExternalId + "bis";
		testDomainHelper.modifyDomainPropertiesFile(newExternalId, isFilesystemLegacy);

		// manual sync with GET /domains/{id}/properties
		final DomainProperties newPropsFromAPI = testDomain.getDomainPropertiesResource().getDomainProperties();
		final String externalIdFromAPI = newPropsFromAPI.getExternalId();
		assertEquals(externalIdFromAPI, newExternalId, "Manual sync of externalId with GET /domains/" + this.testDomainId + "/properties failed: externalId returned (" + externalIdFromAPI
		        + ") does not match externalId in modified file: " + testDomainHelper.getPropertiesFile());

		verifySyncAfterDomainPropertiesFileModification(newExternalId);
	}

	@Parameters({ "enablePdpOnly" })
	@Test(dependsOnMethods = { "getDomain" })
	public void getPap(@Optional("false") final Boolean enablePdpOnly)
	{
		try
		{
			final ResourceContent papResource = testDomain.getPapResource().getPAP();
			assertNotNull(papResource);
			assertFalse(enablePdpOnly, "getPAP method allowed but enablePdpOnly=true");
		}
		catch (final ServerErrorException e)
		{
			assertTrue(enablePdpOnly, "getPAP method not allowed but enablePdpOnly=false");
		}
	}

	@Parameters({ "enablePdpOnly" })
	@Test(dependsOnMethods = { "getPap" })
	public void getAttributeProviders(@Optional("false") final Boolean enablePdpOnly)
	{
		try
		{
			final AttributeProviders attributeProviders = testDomain.getPapResource().getAttributeProvidersResource().getAttributeProviderList();
			assertNotNull(attributeProviders);
			assertFalse(enablePdpOnly, "getAttributeProviderList method allowed but enablePdpOnly=true");
		}
		catch (final ServerErrorException e)
		{
			assertTrue(enablePdpOnly, "getAttributeProviderList method not allowed but enablePdpOnly=false");
		}
	}

	@Parameters({ "enablePdpOnly" })
	@Test(dependsOnMethods = { "getAttributeProviders" })
	public void updateAttributeProviders(@Optional("false") final Boolean enablePdpOnly) throws JAXBException
	{
		final AttributeProvidersResource attributeProvidersResource = testDomain.getPapResource().getAttributeProvidersResource();
		final JAXBElement<TestAttributeProviderDescriptor> jaxbElt = testDomainHelper.unmarshalRestApiEntity(new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp/default_features/xml/IIA002(PolicySet)/attributeProvider.xml"),
		        TestAttributeProviderDescriptor.class);
		final TestAttributeProviderDescriptor testAttrProviderDesc = jaxbElt.getValue();
		final AttributeProviders updateAttrProviderDescsResult;
		try
		{
			updateAttrProviderDescsResult = attributeProvidersResource.updateAttributeProviderList(new AttributeProviders(Collections.singletonList(testAttrProviderDesc)));
			assertFalse(enablePdpOnly, "updateAttributeProviderList method allowed but enablePdpOnly=true");
		}
		catch (final ServerErrorException e)
		{
			assertTrue(enablePdpOnly, "updateAttributeProviderList method not allowed but enablePdpOnly=false");
			return;
		}

		assertNotNull(updateAttrProviderDescsResult);
		assertEquals(updateAttrProviderDescsResult.getAttributeProviders().size(), 1);
		final AbstractAttributeProvider updateAttrProvidersItem = updateAttrProviderDescsResult.getAttributeProviders().get(0);
		if (updateAttrProvidersItem instanceof TestAttributeProviderDescriptor)
		{
			assertEquals(((TestAttributeProviderDescriptor) updateAttrProvidersItem).getAttributes(), testAttrProviderDesc.getAttributes());
		}
		else
		{
			fail("AttributeProvider in result of updateAttributeProviderList(inputAttributeProviders) does not match the one in inputAttributeProviders: " + updateAttrProvidersItem);
		}

		// check getAttributeProviders
		final AttributeProviders getAttrProviderDescsResult = attributeProvidersResource.getAttributeProviderList();
		assertNotNull(getAttrProviderDescsResult);
		assertEquals(getAttrProviderDescsResult.getAttributeProviders().size(), 1);
		final AbstractAttributeProvider getAttrProvidersItem = getAttrProviderDescsResult.getAttributeProviders().get(0);
		if (getAttrProvidersItem instanceof TestAttributeProviderDescriptor)
		{
			assertEquals(((TestAttributeProviderDescriptor) getAttrProvidersItem).getAttributes(), testAttrProviderDesc.getAttributes());
		}
		else
		{
			fail("AttributeProvider in result of updateAttributeProviderList(inputAttributeProviders) does not match the one in inputAttributeProviders: " + getAttrProvidersItem);
		}

	}

	@Parameters({ "enablePdpOnly" })
	@Test(dependsOnMethods = { "getPap" })
	public void getPolicies(@Optional("false") final Boolean enablePdpOnly)
	{
		try
		{
			final Resources resources = testDomain.getPapResource().getPoliciesResource().getPolicies();
			assertNotNull(resources);
			assertTrue(resources.getLinks().size() > 0, "No resource for root policy found");
			assertFalse(enablePdpOnly, "getPolicies method allowed but enablePdpOnly=true");
		}
		catch (final ServerErrorException e)
		{
			assertTrue(enablePdpOnly, "getPolicies method not allowed but enablePdpOnly=false");
		}
	}

	@Parameters({ "enablePdpOnly" })
	@Test(dependsOnMethods = { "getDomainProperties", "getPolicies" })
	public void getRootPolicy(@Optional("false") final Boolean enablePdpOnly)
	{
		final IdReferenceType rootPolicyRef;
		try
		{
			rootPolicyRef = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef();
			assertFalse(enablePdpOnly, "getOtherPdpProperties method allowed but enablePdpOnly=true");
		}
		catch (final ServerErrorException e)
		{
			assertTrue(enablePdpOnly, "getOtherPdpProperties method not allowed but enablePdpOnly=false");
			return;
		}

		final List<Link> links = testDomain.getPapResource().getPoliciesResource().getPolicyResource(rootPolicyRef.getValue()).getPolicyVersions().getLinks();
		assertTrue(links.size() > 0, "No root policy version found");
	}

	private static final String TEST_POLICY_ID0 = "testPolicyAdd";

	@Parameters({ "enablePdpOnly" })
	@Test(dependsOnMethods = { "getRootPolicy" })
	public void addAndGetPolicy(@Optional("false") final Boolean enablePdpOnly)
	{
		final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_ID0, "1.0");
		try
		{
			testDomainHelper.testAddAndGetPolicy(policySet);
			assertFalse(enablePdpOnly, "addPolicy method allowed but enablePdpOnly=true");
		}
		catch (final ServerErrorException e)
		{
			assertTrue(enablePdpOnly, "addPolicy method not allowed but enablePdpOnly=false");
			return;
		}

		final PolicySet policySet2 = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_ID0, "1.1");
		testDomainHelper.testAddAndGetPolicy(policySet2);

		final PolicySet policySet3 = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_ID0, "1.2");
		testDomainHelper.testAddAndGetPolicy(policySet3);
		final Resources policyVersionsResources = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_ID0).getPolicyVersions();
		assertEquals(policyVersionsResources.getLinks().size(), 3);

	}

	@Parameters({ "enablePdpOnly" })
	@Test(dependsOnMethods = { "addAndGetPolicy" })
	public void addInvalidPolicyType(@Optional("false") final Boolean enablePdpOnly)
	{
		if (enablePdpOnly)
		{
			// skip
			return;
		}
		/*
		 * Push XACML Policy instead of PolicySet, although only PolicySets are allowed
		 */
		final Policy policy = RestServiceTest.createDumbXacmlPolicy(TEST_POLICY_ID0, "1.0");
		final javax.ws.rs.core.Response resp = httpClient.reset().path("domains").path(testDomainId).path("pap").path("policies").accept(MediaType.APPLICATION_XML_TYPE)
		        .type(MediaType.APPLICATION_XML_TYPE).post(policy);
		assertEquals(resp.getStatusInfo().getStatusCode(), Status.BAD_REQUEST.getStatusCode(),
		        "Server did not return 400 Bad Request as expected for attempt to upload XACML Policy instead of PolicySet");
	}

	@Parameters({ "enablePdpOnly" })
	@Test(dependsOnMethods = { "addAndGetPolicy" }, expectedExceptions = { BadRequestException.class })
	public void addInvalidPolicyVersion(@Optional("false") final Boolean enablePdpOnly)
	{
		if (enablePdpOnly)
		{
			// skip
			return;
		}
		/*
		 * Push XACML PolicySet with invalid version (test especially validation of XML schema with JSON input)
		 */
		final PolicySet policy = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_ID0, "");
		testDomainHelper.testAddAndGetPolicy(policy);
	}

	private static final String TEST_POLICY_ID1 = "policyToTestGetVersions";

	static boolean isHrefMatched(final String href, final List<Link> links)
	{
		for (final Link link : links)
		{
			if (link.getHref().equals(href))
			{
				return true;
			}
		}

		return false;
	}

	@Test(dependsOnMethods = { "addAndGetPolicy" })
	public void getPolicyVersions()
	{
		final String[] policyVersions = { "1.0", "1.1", "1.2" };
		for (final String v : policyVersions)
		{
			final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_ID1, v);
			testDomainHelper.testAddAndGetPolicy(policySet);
		}

		final PoliciesResource policiesRes = testDomain.getPapResource().getPoliciesResource();
		final PolicyResource policyRes = policiesRes.getPolicyResource(TEST_POLICY_ID1);
		final List<Link> versionLinks = policyRes.getPolicyVersions().getLinks();

		assertEquals(policyVersions.length, versionLinks.size(), "Invalid number of versions returned by getPolicyVersions()");
		for (final String v : policyVersions)
		{
			assertTrue(isHrefMatched(v, versionLinks), "Version missing in list returned by API getPolicyVersions()");
		}
	}

	private static final String TEST_POLICY_ID2 = "policyToTestGetLatestV";

	@Test(dependsOnMethods = { "getPolicyVersions" })
	public void getAndDeleteLatestPolicyVersion()
	{
		final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_ID2, "1.0");
		testDomainHelper.testAddAndGetPolicy(policySet);

		final PolicySet policySet2 = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_ID2, "1.1");
		testDomainHelper.testAddAndGetPolicy(policySet2);

		final PolicySet policySet3 = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_ID2, "1.2");
		testDomainHelper.testAddAndGetPolicy(policySet3);

		final PoliciesResource policiesRes = testDomain.getPapResource().getPoliciesResource();
		final PolicyResource policyRes = policiesRes.getPolicyResource(TEST_POLICY_ID2);
		final PolicySet getRespPolicySet = policyRes.getPolicyVersionResource("latest").getPolicyVersion();
		DomainAPIHelper.matchPolicySets(getRespPolicySet, policySet3, "getAndDeleteLatestPolicyVersion");

		final PolicySet deleteRespPolicySet = policyRes.getPolicyVersionResource("latest").deletePolicyVersion();
		DomainAPIHelper.matchPolicySets(deleteRespPolicySet, policySet3, "getAndDeleteLatestPolicyVersion");
	}

	@Test(dependsOnMethods = { "addAndGetPolicy" })
	public void setValidMaxPolicyCount() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();

		final int maxPolicyCount = 3;
		final int actualMax = testDomainHelper.updateAndGetMaxPolicyCount(maxPolicyCount);
		assertEquals(maxPolicyCount, actualMax, "Max policy count set with PUT does not match the one retrieve with GET");
		// reset maxpolicycount to undefined for other tests
		testDomainHelper.updateMaxPolicyCount(-1);
	}

	@Test(dependsOnMethods = { "setValidMaxPolicyCount" }, expectedExceptions = { BadRequestException.class })
	public void setTooSmallMaxPolicyCount() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();

		final int maxPolicyCount = 3;
		testDomainHelper.updateMaxPolicyCount(maxPolicyCount);

		for (int i = 0; i < maxPolicyCount - 1; i++)
		{
			final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet("policy_setTooSmallMaxPolicyCount" + i, "1.0");
			testDomainHelper.testAddAndGetPolicy(policySet);
		}

		// try setting max policy count lower than current number of policies
		testDomainHelper.updateMaxPolicyCount(maxPolicyCount - 1);
	}

	@Test(dependsOnMethods = { "addAndGetPolicy" }, expectedExceptions = { ForbiddenException.class })
	public void addTooManyPolicies() throws JAXBException
	{
		// replace all policies with one root policy and this is the only one policy
		testDomainHelper.resetPdpAndPrp();

		final int maxPolicyCountPerDomain = 3;
		testDomainHelper.updateMaxPolicyCount(maxPolicyCountPerDomain);

		// So we can only add maxPolicyCountPerDomain-1 more policies before reaching the max
		for (int i = 0; i < maxPolicyCountPerDomain - 1; i++)
		{
			final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet("policyTooMany" + i, "1.0");
			testDomainHelper.testAddAndGetPolicy(policySet);
		}

		// verify that all policies are there
		final List<Link> links = testDomain.getPapResource().getPoliciesResource().getPolicies().getLinks();
		final Set<Link> policyLinkSet = new HashSet<>(links);
		assertEquals(links.size(), policyLinkSet.size(), "Duplicate policies returned in links from getPolicies: " + links);

		assertEquals(policyLinkSet.size(), maxPolicyCountPerDomain, "policies removed before reaching value of property 'org.ow2.authzforce.domain.maxPolicyCount'. Actual versions: " + links);

		// We should have reached the max, so adding one more should be rejected by the server
		final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet("policyTooMany", "1.0");
		// this should raise ForbiddenException
		testDomainHelper.testAddAndGetPolicy(policySet);
		fail("Failed to enforce maxPoliciesPerDomain property: " + maxPolicyCountPerDomain);
	}

	private static final String TEST_POLICY_ID = "policyTooManyV";

	@Parameters({ "enablePdpOnly" })
	@Test(dependsOnMethods = { "addAndGetPolicy" })
	public void disablePolicyVersionRolling(@Optional("false") final Boolean enablePdpOnly)
	{
		try
		{
			final boolean isEnabled = testDomainHelper.setPolicyVersionRollingAndGetStatus(false);
			assertFalse(isEnabled, "Failed to disable policy version rolling");
			assertFalse(enablePdpOnly, "getOtherPrpProperties method allowed but enablePdpOnly=true");
		}
		catch (final ServerErrorException e)
		{
			assertTrue(enablePdpOnly, "getOtherPrpProperties method not allowed but enablePdpOnly=false");
		}
	}

	@Test(dependsOnMethods = { "disablePolicyVersionRolling" })
	public void setValidMaxPolicyVersionCount() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();

		final int maxPolicyVersionCount = 3;
		final int actualMax = testDomainHelper.updateAndGetMaxPolicyVersionCount(maxPolicyVersionCount);
		assertEquals(maxPolicyVersionCount, actualMax, "Max policy count set with PUT does not match the one retrieve with GET");
		// reset max version count for other tests
		testDomainHelper.updateVersioningProperties(-1, false);
	}

	@Test(dependsOnMethods = { "setValidMaxPolicyVersionCount" }, expectedExceptions = { BadRequestException.class })
	public void setTooSmallMaxPolicyVersionCount() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();

		final int maxVersionCountPerPolicy = 3;
		testDomainHelper.updateVersioningProperties(maxVersionCountPerPolicy, false);

		for (int i = 0; i < maxVersionCountPerPolicy; i++)
		{
			final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_ID, "1." + i);
			testDomainHelper.testAddAndGetPolicy(policySet);
		}

		// try setting max policy count lower than current number of policies
		testDomainHelper.updateVersioningProperties(maxVersionCountPerPolicy - 1, false);
	}

	@Test(dependsOnMethods = { "addTooManyPolicies" }, expectedExceptions = { ForbiddenException.class })
	public void addTooManyPolicyVersions() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();

		final int maxVersionCountPerPolicy = 3;
		testDomainHelper.updateVersioningProperties(maxVersionCountPerPolicy, false);
		for (int i = 0; i < maxVersionCountPerPolicy; i++)
		{
			final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_ID, "1." + i);
			testDomainHelper.testAddAndGetPolicy(policySet);
		}

		// verify that all versions are there
		final List<Link> links = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_ID).getPolicyVersions().getLinks();
		final Set<Link> versionSet = new HashSet<>(links);
		assertEquals(links.size(), versionSet.size(), "Duplicate versions returned in links from getPolicyResource(policyId): " + links);

		assertEquals(versionSet.size(), maxVersionCountPerPolicy, "versions removed before reaching maxVersionCountPerPolicy'. Actual versions: " + links);

		final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_ID, "2.0");
		// this should raise ForbiddenException
		testDomainHelper.testAddAndGetPolicy(policySet);
		fail("Failed to enforce property 'maxVersionCountPerPolicy': " + maxVersionCountPerPolicy);
	}

	@Test(dependsOnMethods = { "addAndGetPolicy" })
	public void addConflictingPolicyVersion()
	{
		final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet("testAddConflictingPolicyVersion", "1.0");
		testDomainHelper.testAddAndGetPolicy(policySet);

		// must be rejected
		try
		{
			testDomainHelper.testAddAndGetPolicy(policySet);
			fail("Adding the same policy did not fail with HTTP 409 Conflict as expected");
		}
		catch (final ClientErrorException e)
		{
			assertEquals(e.getResponse().getStatus(), Status.CONFLICT.getStatusCode());
		}
	}

	private static final String TEST_POLICY_DELETE_ID = "testPolicyDelete";

	@Test(dependsOnMethods = { "addAndGetPolicy" })
	public void deleteAndTryGetUnusedPolicy() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();
		final PolicySet policySet1 = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_DELETE_ID, "1.2.3");
		testDomainHelper.testAddAndGetPolicy(policySet1);

		final PolicySet policySet2 = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_DELETE_ID, "1.3.1");
		testDomainHelper.testAddAndGetPolicy(policySet2);

		// delete policy (all versions)
		final PolicyResource policyResource = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_DELETE_ID);
		final Resources versionsResources = policyResource.deletePolicy();
		assertNotNull(versionsResources);
		assertNotNull(DomainAPIHelper.getMatchingLink("1.2.3", versionsResources.getLinks()));
		assertNotNull(DomainAPIHelper.getMatchingLink("1.3.1", versionsResources.getLinks()));

		try
		{
			policyResource.getPolicyVersions();
			fail("Policy (all versions) removal failed (resource still there");
		}
		catch (final NotFoundException e)
		{
			// OK
		}

		final PoliciesResource policiesRes = testDomain.getPapResource().getPoliciesResource();
		assertNull(DomainAPIHelper.getMatchingLink(TEST_POLICY_DELETE_ID, policiesRes.getPolicies().getLinks()),
		        "Deleted policy resource (all versions) is still in links returned by getPoliciesResource()");
	}

	@Test(dependsOnMethods = { "deleteAndTryGetUnusedPolicy" })
	public void deleteAndTryGetPolicyVersion()
	{
		final PolicySet policySet1 = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_DELETE_ID, "1.2.3");
		testDomainHelper.testAddAndGetPolicy(policySet1);

		final PolicySet policySet2 = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_DELETE_ID, "1.3.1");
		testDomainHelper.testAddAndGetPolicy(policySet2);

		// delete one of the versions
		final PolicyVersionResource policyVersionRes = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_DELETE_ID).getPolicyVersionResource("1.2.3");
		final PolicySet deletedPolicy = policyVersionRes.deletePolicyVersion();
		DomainAPIHelper.matchPolicySets(deletedPolicy, policySet1, "deleteAndTryGetPolicyVersion");

		try
		{
			policyVersionRes.getPolicyVersion();
			org.testng.Assert.fail("Policy version removal failed (resource still there");
		}
		catch (final NotFoundException e)
		{
			// OK
		}

		final Resources policyVersionsResources = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_DELETE_ID).getPolicyVersions();
		assertEquals(policyVersionsResources.getLinks().size(), 1);
		assertNotNull(DomainAPIHelper.getMatchingLink("1.3.1", policyVersionsResources.getLinks()));
	}

	private static final String TEST_POLICY_DELETE_SINGLE_VERSION_ID = "testPolicyDeleteSingleVersion";

	@Test(dependsOnMethods = { "deleteAndTryGetPolicyVersion" }, expectedExceptions = { NotFoundException.class })
	public void deleteSingleVersionOfPolicy()
	{
		final PolicySet policySet1 = RestServiceTest.createDumbXacmlPolicySet(TEST_POLICY_DELETE_SINGLE_VERSION_ID, "1.2.3");
		testDomainHelper.testAddAndGetPolicy(policySet1);

		final PolicyResource policyRes = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_DELETE_SINGLE_VERSION_ID);
		// delete this single version
		final PolicyVersionResource policyVersionRes = policyRes.getPolicyVersionResource("1.2.3");
		// this should have the same effect as removing the policy altogether, as this is the only remaining version
		policyVersionRes.deletePolicyVersion();
		/*
		 * Check that the policy is completely removed
		 */
		final List<Link> policyLinks = testDomain.getPapResource().getPoliciesResource().getPolicies().getLinks();
		assertNull(DomainAPIHelper.getMatchingLink(TEST_POLICY_DELETE_SINGLE_VERSION_ID, policyLinks), "Policy removal after removing last remaining version failed");
		/*
		 * Should throw NotFoundException
		 */
		policyRes.getPolicyVersions();
		fail("Policy removal after removing last remaining version failed");
	}

	@Test(dependsOnMethods = { "getRootPolicy", "deleteAndTryGetUnusedPolicy" })
	public void deleteRootPolicy()
	{
		final IdReferenceType rootPolicyRef = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef();

		// must be rejected
		try
		{
			testDomain.getPapResource().getPoliciesResource().getPolicyResource(rootPolicyRef.getValue()).deletePolicy();
			fail("Wrongly accepted to remove root policy");
		}
		catch (final BadRequestException e)
		{
			// OK, expected
		}

		assertNotNull(testDomain.getPapResource().getPoliciesResource().getPolicyResource(rootPolicyRef.getValue()).getPolicyVersions(),
		        "Root policy was actually removed although server return HTTP 400 for removal attempt");

		// make sure rootPolicyRef unchanged
		assertEquals(rootPolicyRef, testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef(),
		        "rootPolicyRef changed although root policy removal rejected");
	}

	@Test(dependsOnMethods = { "addAndGetPolicy", "deleteRootPolicy", "updateDomainProperties" })
	public void updateRootPolicyRefToValidPolicy()
	{
		final PdpPropertiesResource propsRes = testDomain.getPapResource().getPdpPropertiesResource();

		// get current root policy ID
		final IdReferenceType rootPolicyRef = propsRes.getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef();
		final PolicyResource oldRootPolicyRes = testDomain.getPapResource().getPoliciesResource().getPolicyResource(rootPolicyRef.getValue());

		// point rootPolicyRef to another one
		final String oldRootPolicyId = rootPolicyRef.getValue();
		final String newRootPolicyId = oldRootPolicyId + ".new";
		final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet(newRootPolicyId, "1.0");
		final IdReferenceType newRootPolicyRef = testDomainHelper.setRootPolicy(policySet, false);

		// verify update
		final PdpProperties newProps = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties();
		assertEquals(newProps.getApplicablePolicies().getRootPolicyRef(), newRootPolicyRef,
		        "Root PolicyRef returned by getOtherPdpProperties() does not match last set rootPolicyRef via updateOtherPdpProperties()");

		// delete previous root policy ref to see if it succeeds
		oldRootPolicyRes.deletePolicy();
	}

	@Test(dependsOnMethods = { "updateRootPolicyRefToValidPolicy" })
	public void updateRootPolicyRefToMissingPolicy()
	{
		final PdpPropertiesResource propsRes = testDomain.getPapResource().getPdpPropertiesResource();
		final PdpProperties props = propsRes.getOtherPdpProperties();

		// get current root policy ID
		final IdReferenceType rootPolicyRef = props.getApplicablePolicies().getRootPolicyRef();

		// point rootPolicyRef to another one
		final String oldRootPolicyId = rootPolicyRef.getValue();
		final String newRootPolicyId = oldRootPolicyId + ".new";

		final IdReferenceType newRootPolicyRef = new IdReferenceType(newRootPolicyId, null, null, null);

		// MUST fail
		try
		{
			propsRes.updateOtherPdpProperties(new PdpPropertiesUpdate(null, newRootPolicyRef));
			fail("Setting rootPolicyRef to missing policy did not fail as expected");
		}
		catch (final BadRequestException e)
		{
			// OK
		}

		// make sure rootPolicyRef unchanged
		assertEquals(rootPolicyRef, testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef());
	}

	@Test(dependsOnMethods = { "updateRootPolicyRefToValidPolicy" })
	public void updateRootPolicyRefToValidVersion()
	{
		testDomainHelper.updateMaxPolicyCount(-1);
		testDomainHelper.updateVersioningProperties(-1, false);

		final PdpPropertiesResource propsRes = testDomain.getPapResource().getPdpPropertiesResource();

		final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet("testUpdateRootPolicyRefToValidVersion", "4.4");
		testDomainHelper.testAddAndGetPolicy(policySet);

		final PolicySet policySet2 = RestServiceTest.createDumbXacmlPolicySet("testUpdateRootPolicyRefToValidVersion", "4.5");
		testDomainHelper.testAddAndGetPolicy(policySet2);

		final PolicySet policySet3 = RestServiceTest.createDumbXacmlPolicySet("testUpdateRootPolicyRefToValidVersion", "4.6");
		testDomainHelper.testAddAndGetPolicy(policySet3);

		final IdReferenceType newRootPolicyRef = new IdReferenceType("testUpdateRootPolicyRefToValidVersion", "4.5", null, null);
		propsRes.updateOtherPdpProperties(new PdpPropertiesUpdate(null, newRootPolicyRef));

		// verify update
		final PdpProperties newProps = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties();
		assertEquals(newProps.getApplicablePolicies().getRootPolicyRef(), newRootPolicyRef);

		// delete other policy versions (must succeed) to check that the root
		// policyRef is not pointing to one of them by mistake
		final PolicyResource policyRes = testDomain.getPapResource().getPoliciesResource().getPolicyResource("testUpdateRootPolicyRefToValidVersion");
		policyRes.getPolicyVersionResource("4.4").deletePolicyVersion();
		policyRes.getPolicyVersionResource("4.6").deletePolicyVersion();
	}

	@Test(dependsOnMethods = { "updateRootPolicyRefToValidVersion" })
	public void updateRootPolicyRefToMissingVersion()
	{
		final PdpPropertiesResource propsRes = testDomain.getPapResource().getPdpPropertiesResource();
		final PdpProperties props = propsRes.getOtherPdpProperties();

		// get current root policy ID
		final IdReferenceType rootPolicyRef = props.getApplicablePolicies().getRootPolicyRef();

		// point rootPolicyRef to same policy id but different version
		final String rootPolicyId = rootPolicyRef.getValue();
		final IdReferenceType newRootPolicyRef = new IdReferenceType(rootPolicyId, "0.0.0.1", null, null);
		// MUST FAIL
		try
		{
			propsRes.updateOtherPdpProperties(new PdpPropertiesUpdate(null, newRootPolicyRef));
			fail("Setting rootPolicyRef to missing policy version did not fail as expected.");
		}
		catch (final BadRequestException e)
		{
			// OK
		}

		// make sure rootPolicyRef unchanged
		assertEquals(rootPolicyRef, testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef());
	}

	@Test(dependsOnMethods = { "updateRootPolicyRefToValidPolicy" })
	public void setRootPolicyWithGoodRefs() throws JAXBException
	{
		/*
		 * Before putting the empty refPolicySets (to make a policySets with references wrong), first put good root policySet without policy references (in case the current root PolicySet has
		 * references, uploading the empty refPolicySets before will be rejected because it makes the policySet with references invalid)
		 */
		testDomainHelper.resetPdpAndPrp();

		final JAXBElement<PolicySet> jaxbElement = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp/default_features/xml/PolicyReference.Valid/refPolicies/pps-employee.xml"),
		        PolicySet.class);
		final PolicySet refPolicySet = jaxbElement.getValue();
		final String refPolicyResId = testDomainHelper.testAddAndGetPolicy(refPolicySet);

		// Set root policy referencing ref policy above
		final JAXBElement<PolicySet> jaxbElement2 = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp/default_features/xml/PolicyReference.Valid/policy.xml"), PolicySet.class);
		final PolicySet policySetWithRef = jaxbElement2.getValue();
		// Add the policy and point the rootPolicyRef to new policy with refs to
		// instantiate it as root policy (validate, etc.)
		testDomainHelper.setRootPolicy(policySetWithRef, true);

		// Delete referenced policy -> must fail (because root policy is still
		// referencing one of them)
		final PolicyResource refPolicyResource = testDomain.getPapResource().getPoliciesResource().getPolicyResource(refPolicyResId);
		try
		{
			refPolicyResource.deletePolicy();
			fail("Policy used/referenced by root policy was deleted, making root policy invalid");
		}
		catch (final BadRequestException e)
		{
			// Bad request as expected
		}

		/*
		 * Check that referenced policy set was not actually deleted, is still there
		 */
		final PolicySet getPolicyVersion = refPolicyResource.getPolicyVersionResource(refPolicySet.getVersion()).getPolicyVersion();
		assertNotNull(getPolicyVersion);
	}

	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void setRootPolicyWithRefToMissingPolicy() throws JAXBException
	{
		/*
		 * Before putting the empty refPolicySets (to make a policySets with references wrong), first put good root policySet without policy references (in case the current root PolicySet has
		 * references, uploading the empty refPolicySets before will be rejected because it makes the policySet with references invalid)
		 */
		testDomainHelper.resetPdpAndPrp();
		// Get current rootPolicy
		final IdReferenceType rootPolicyRef = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef();

		// Then attempt to put bad root policy set (referenced policysets in
		// Policy(Set)IdReferences do
		// not exist since refPolicySets empty)
		final JAXBElement<PolicySet> policySetWithRefs = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.Undef/policy.xml"), PolicySet.class);
		try
		{
			testDomainHelper.setRootPolicy(policySetWithRefs.getValue(), true);
			fail("Invalid Root PolicySet (with invalid references) accepted");
		}
		catch (final BadRequestException e)
		{
			// Bad request as expected
		}

		// make sure the rootPolicyRef is unchanged
		assertEquals(rootPolicyRef, testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef(),
		        "rootPolicyRef changed although root policy update rejected");
	}

	@Test(dependsOnMethods = { "setRootPolicyWithRefToMissingPolicy" })
	public void setRootPolicyWithBadFunctionId() throws JAXBException
	{

		// Get current rootPolicyRef
		final IdReferenceType rootPolicyRef = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef();

		// Then attempt to put bad root policy set (invalid function ID)
		final JAXBElement<PolicySet> rootPolicyJaxbElt = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_SAMPLES_DIR, "policyWithBadFunctionId.xml"), PolicySet.class);
		final PolicySet rootPolicy = rootPolicyJaxbElt.getValue();
		try
		{
			testDomainHelper.setRootPolicy(rootPolicy, true);
			fail("Invalid Root PolicySet (with invalid function ID) accepted");
		}
		catch (final BadRequestException e)
		{
			/*
			 * Bad request as expected.
			 * 
			 */
			if (LOGGER.isDebugEnabled())
			{
				try (final javax.ws.rs.core.Response resp = e.getResponse())
				{
					LOGGER.debug("BadRequestException for test method setRootPolicyWithBadFunctionId() as expected with payload: {}", resp.readEntity(String.class));
				}
			}
		}

		// make sure the rootPolicyRef is unchanged
		assertEquals(rootPolicyRef, testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef(),
		        "rootPolicyRef changed although root policy update rejected");

		/*
		 * Make sure the policy was not saved/persisted anyway (see https://github.com/authzforce/server/issues/45 )
		 */
		try
		{
			testDomainHelper.getPolicy(rootPolicy.getPolicySetId(), rootPolicy.getVersion());
			fail("Invalid Root PolicySet (with invalid function Id) was saved and still there on server side, although HTTP 400 BadRequest returned when trying to push it");
		}
		catch (final NotFoundException e)
		{
			/*
			 * Policy does not exist on server side as expected.
			 */
		}

	}

	@Test(dependsOnMethods = { "setRootPolicyWithBadFunctionId" })
	public void addPolicyWithBadCombiningAlgId() throws JAXBException
	{
		// Then attempt to put bad root policy set (invalid function ID)
		final JAXBElement<PolicySet> badPolicyJaxbElt = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_SAMPLES_DIR, "policyWithBadCombiningAlgId.xml"), PolicySet.class);
		final PolicySet badPolicy = badPolicyJaxbElt.getValue();
		try
		{
			testDomain.getPapResource().getPoliciesResource().addPolicy(badPolicy);
			fail("Invalid PolicySet (with invalid CombiningAlgId) accepted");
		}
		catch (final BadRequestException e)
		{
			/*
			 * Bad request as expected.
			 * 
			 */
			if (LOGGER.isDebugEnabled())
			{
				try (final javax.ws.rs.core.Response resp = e.getResponse())
				{
					LOGGER.debug("BadRequestException for test method addPolicyWithBadCombiningAlgId() as expected with payload: {}", resp.readEntity(String.class));
				}
			}

		}

		/*
		 * Make sure the policy was not saved/persisted anyway (see https://github.com/authzforce/server/issues/45 )
		 */
		try
		{
			testDomainHelper.getPolicy(badPolicy.getPolicySetId(), badPolicy.getVersion());
			fail("Invalid PolicySet (with invalid CombiningAlgId) was saved and still there on server side, although HTTP 400 BadRequest returned when trying to push it");
		}
		catch (final NotFoundException e)
		{
			/*
			 * Policy does not exist on server side as expected.
			 */
		}
	}

	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void setRootPolicyWithCircularRef() throws JAXBException
	{
		/*
		 * Before putting the empty refPolicySets (to make a policySets with references wrong), first put good root policySet without policy references (in case the current root PolicySet has
		 * references, uploading the empty refPolicySets before will be rejected because it makes the policySet with references invalid)
		 */
		testDomainHelper.resetPdpAndPrp();
		// Get current rootPolicy
		final IdReferenceType rootPolicyRef = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef();

		// add refPolicies
		final File employeeRolePpsFile = new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.Circular/refPolicies/invalid-pps-employee.xml");
		final JAXBElement<PolicySet> employeeRolePps = testDomainHelper.unmarshalXacml(employeeRolePpsFile, PolicySet.class);

		/*
		 * Every Policy is validated on the server-side before being committed to the server's policy repository This PPS:Employee policy must be rejected because it refers to PPS:Manager which is not
		 * set yet.
		 */
		try
		{
			testDomainHelper.testAddAndGetPolicy(employeeRolePps.getValue());
			fail("Invalid Employee role PPS (with invalid policy reference to non-existent Manager role's PPS) accepted although must not be. Policy file: " + employeeRolePpsFile);
		}
		catch (final BadRequestException e)
		{
			// Bad request as expected
		}

		/*
		 * Similarly, the PPS:Manager policy must be rejected because it refers to PPS:Employee which has already been rejected.
		 */
		final File managerRolePpsFile = new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.Circular/refPolicies/pps-manager.xml");
		final JAXBElement<PolicySet> managerRolePps = testDomainHelper.unmarshalXacml(managerRolePpsFile, PolicySet.class);
		try
		{
			testDomainHelper.testAddAndGetPolicy(managerRolePps.getValue());
			fail("Invalid Manager role PPS (with invalid policy reference to non-existent Employee role's PPS) accepted although must not be. Policy File: " + managerRolePpsFile);
		}
		catch (final BadRequestException e)
		{
			// Bad request as expected
		}

		/*
		 * Then attempt to put bad root policy set (referenced policysets in Policy(Set)IdReferences do not exist since refPolicySets empty)
		 */
		final File rootPolicyFile = new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.Circular/policy.xml");
		final JAXBElement<PolicySet> policySetWithRefs = testDomainHelper.unmarshalXacml(rootPolicyFile, PolicySet.class);
		try
		{
			testDomainHelper.setRootPolicy(policySetWithRefs.getValue(), true);
			fail("Invalid Root PolicySet (with invalid/non-existent references to Employee and Manager roles' PPS) accepted although must not be. Policy file: " + rootPolicyFile);
		}
		catch (final BadRequestException e)
		{
			// Bad request as expected
		}

		// make sure the rootPolicyRef is unchanged
		assertEquals(rootPolicyRef, testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef(),
		        "rootPolicyRef changed although root policy update rejected");
	}

	/**
	 * This tests property 'org.apache.cxf.stax.maxChildElements' = {@value #XML_MAX_CHILD_ELEMENTS}
	 * 
	 * @throws JAXBException error
	 */
	@Test(dependsOnMethods = { "addAndGetPolicy" }, expectedExceptions = NotFoundException.class)
	public void addPolicyWithTooManyChildElements() throws JAXBException
	{
		final JAXBElement<PolicySet> badPolicySetJaxbObj = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_SAMPLES_DIR, "policyWithTooManyChildElements.xml"), PolicySet.class);
		final PolicySet badPolicySet = badPolicySetJaxbObj.getValue();
		try
		{
			testDomainHelper.testAddAndGetPolicy(badPolicySet);
			fail("Invalid PolicySet (too many child elements) accepted");
		}
		catch (final BadRequestException e)
		{
			// Bad request as expected
		}
		catch (final ClientErrorException e)
		{
			assertEquals(e.getResponse().getStatus(), Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
		}

		// make sure the policy is not there
		// MUST throw NotFoundException
		testDomain.getPapResource().getPoliciesResource().getPolicyResource(badPolicySet.getPolicySetId()).getPolicyVersions();
	}

	@Test(dependsOnMethods = { "addAndGetPolicy" }, expectedExceptions = NotFoundException.class)
	public void addPolicyTooDeep() throws JAXBException
	{
		final JAXBElement<PolicySet> jaxbObj = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_SAMPLES_DIR, "policyTooDeep.xml"), PolicySet.class);
		final PolicySet badPolicySet = jaxbObj.getValue();
		try
		{
			testDomainHelper.testAddAndGetPolicy(badPolicySet);
			fail("Invalid PolicySet (too deep element(s)) accepted");
		}
		catch (final BadRequestException e)
		{
			// Bad request as expected
		}
		catch (final ClientErrorException e)
		{
			// When using FastInfoset, the error is not Bad Request but 413 Request Entity Too Large
			assertEquals(e.getResponse().getStatus(), Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
		}

		// make sure the policy is not there
		// MUST throw NotFoundException
		testDomain.getPapResource().getPoliciesResource().getPolicyResource(badPolicySet.getPolicySetId()).getPolicyVersions();
	}

	/**
	 * Create PDP evaluation test data. Various PolicySets/Requests/Responses from conformance tests.
	 * 
	 * 
	 * @return iterator over test data
	 */
	@DataProvider(name = "pdpTestFiles")
	public Iterator<Object[]> createData()
	{
		final Collection<Object[]> testParams;
		/*
		 * Each sub-directory of the root directory is data for a specific test. So we configure a test for each directory
		 */
		final File testRootDir = new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp/default_features/xml");
		// specific test's resources directory location, used as parameter
		// to PdpTest(String)
		testParams = Arrays.stream(Objects.requireNonNull(testRootDir.listFiles(DIRECTORY_FILTER))).map(subDir -> new Object[]{subDir}).collect(Collectors.toList());
		return testParams.iterator();
	}

	private void testSetFeature(final String featureId, final PdpFeatureType featureType, final boolean enabled)
	{
		final Feature setFeature = new Feature(featureId, featureType.toString(), enabled);
		final List<Feature> inputFeatures = Collections.singletonList(setFeature);
		final List<Feature> outputFeatures = testDomainHelper.updateAndGetPdpFeatures(inputFeatures);
		boolean featureMatched = false;
		final Set<String> features = new HashSet<>();
		for (final Feature outputFeature : outputFeatures)
		{
			final String featureID = outputFeature.getValue();
			assertTrue(features.add(featureID), "Duplicate feature: " + featureID);
			if (outputFeature.getType().equals(featureType.toString()) && outputFeature.getValue().equals(featureId))
			{
				assertEquals(outputFeature.isEnabled(), enabled, "Enabled values of input feature (" + featureId + ") and matching output feature don't match");
				featureMatched = true;
			}
		}

		assertTrue(featureMatched, "No feature '" + featureId + "' found");
	}

	/**
	 * Test enable/disable a PDP core feature: XPath eval (AttributeSelector, XPath datatype/functions)
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableThenDisableXPath(@Optional final String remoteAppBaseUrl) throws JAXBException
	{
		testSetFeature(PdpCoreFeature.XPATH_EVAL.toString(), PdpFeatureType.CORE, true);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final boolean xpathEvalEnabled = testDomainHelper.getPdpConfFromFile().isXPathEnabled();
			assertTrue(xpathEvalEnabled, "Failed to enable XPath support in PDP configuration");
		}

		// Disable XPath support
		testSetFeature(PdpCoreFeature.XPATH_EVAL.toString(), PdpFeatureType.CORE, false);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{@SuppressFBWarnings(value="EI_EXPOSE_REP")
			final boolean xpathEvalEnabled = testDomainHelper.getPdpConfFromFile().isXPathEnabled();
			assertFalse(xpathEvalEnabled, "Failed to disable XPath support in PDP configuration");
		}
	}

	/**
	 * Test enable/disable a PDP core feature: strict Attribute Issuer matching
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableThenDisableStrictAttributeIssuerMatch(@Optional final String remoteAppBaseUrl) throws JAXBException
	{
		/*
		 * Before setting this, the active policy(ies) must have Issuers on all AttributeDesignators
		 */
		final PolicySet policySet = RestServiceTest.createDumbXacmlPolicySet("root", "1.0");
		testDomainHelper.setRootPolicy(policySet, true);
		testSetFeature(PdpCoreFeature.STRICT_ATTRIBUTE_ISSUER_MATCH.toString(), PdpFeatureType.CORE, true);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final boolean xpathEvalEnabled = testDomainHelper.getPdpConfFromFile().isStrictAttributeIssuerMatch();
			assertTrue(xpathEvalEnabled, "Failed to enable strictAttributeIssuerMatch in PDP configuration");
		}

		// Disable XPath support
		testSetFeature(PdpCoreFeature.STRICT_ATTRIBUTE_ISSUER_MATCH.toString(), PdpFeatureType.CORE, false);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final boolean xpathEvalEnabled = testDomainHelper.getPdpConfFromFile().isStrictAttributeIssuerMatch();
			assertFalse(xpathEvalEnabled, "Failed to disable strictAttributeIssuerMatch in PDP configuration");
		}
	}

	/**
	 * Test enable/disable a PDP datatype feature (extension for custom datatype): dnsName-value
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableThenDisableCustomDatatype(@Optional final String remoteAppBaseUrl) throws JAXBException
	{
		testSetFeature(TestDnsNameWithPortValue.DATATYPE.getId(), PdpFeatureType.DATATYPE, true);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final List<String> customDatatypes = testDomainHelper.getPdpConfFromFile().getAttributeDatatypes();
			assertTrue(customDatatypes.contains(TestDnsNameWithPortValue.DATATYPE.getId()),
			        "Failed to enable custom datatype '" + TestDnsNameWithPortValue.DATATYPE.getId() + "' in PDP configuration");
		}

		// Disable XPath support
		testSetFeature(TestDnsNameWithPortValue.DATATYPE.getId(), PdpFeatureType.DATATYPE, false);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final List<String> customDatatypes = testDomainHelper.getPdpConfFromFile().getAttributeDatatypes();
			assertFalse(customDatatypes.contains(TestDnsNameWithPortValue.DATATYPE.getId()),
			        "Failed to disable custom datatype '" + TestDnsNameWithPortValue.DATATYPE.getId() + "' in PDP configuration");
		}
	}

	/**
	 * Test enable/disable a PDP function feature (extension for custom function): dnsName-value-equal
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableThenDisableCustomFunction(@Optional final String remoteAppBaseUrl) throws JAXBException
	{
		testSetFeature(TestDnsNameValueEqualFunction.ID, PdpFeatureType.FUNCTION, true);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final List<String> customFunctions = testDomainHelper.getPdpConfFromFile().getFunctions();
			assertTrue(customFunctions.contains(TestDnsNameValueEqualFunction.ID), "Failed to enable custom function '" + TestDnsNameValueEqualFunction.ID + "' in PDP configuration");
		}

		// Disable XPath support
		testSetFeature(TestDnsNameValueEqualFunction.ID, PdpFeatureType.FUNCTION, false);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final List<String> customFunctions = testDomainHelper.getPdpConfFromFile().getFunctions();
			assertFalse(customFunctions.contains(TestDnsNameValueEqualFunction.ID), "Failed to disable custom function '" + TestDnsNameValueEqualFunction.ID + "' in PDP configuration");
		}
	}

	/**
	 * Test enable/disable a PDP combining algorithm feature (extension for custom policy/rule combining algorithm): on-permit-apply-second
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableThenDisableCustomCombiningAlgorithm(@Optional final String remoteAppBaseUrl) throws JAXBException
	{
		testSetFeature(TestOnPermitApplySecondCombiningAlg.ID, PdpFeatureType.COMBINING_ALGORITHM, true);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final List<String> customAlgorithms = testDomainHelper.getPdpConfFromFile().getCombiningAlgorithms();
			assertTrue(customAlgorithms.contains(TestOnPermitApplySecondCombiningAlg.ID),
			        "Failed to enable custom combining algorithm '" + TestOnPermitApplySecondCombiningAlg.ID + "' in PDP configuration");
		}

		// Disable XPath support
		testSetFeature(TestOnPermitApplySecondCombiningAlg.ID, PdpFeatureType.COMBINING_ALGORITHM, false);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final List<String> customAlgorithms = testDomainHelper.getPdpConfFromFile().getCombiningAlgorithms();
			assertFalse(customAlgorithms.contains(TestOnPermitApplySecondCombiningAlg.ID),
			        "Failed to disable custom combining algorithm '" + TestOnPermitApplySecondCombiningAlg.ID + "' in PDP configuration");
		}
	}

	/**
	 * Test enable/disable a PDP result-postproc feature (extension for custom XACML decision result postprocessor): Multiple Decision Request Profile / CombinedDecision
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableMDPResultPostproc(@Optional final String remoteAppBaseUrl) throws JAXBException
	{
		/*
		 * A request-preproc compatible with the result-postproc we want must be enabled before the result-postproc
		 */
		final Feature mdpReqPreprocFeature = new Feature(MultiDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID, PdpFeatureType.REQUEST_PREPROC.toString(), true);
		final Feature mdpResPostprocFeature = new Feature(TestCombinedDecisionXacmlJaxbResultPostprocessor.Factory.ID, PdpFeatureType.RESULT_POSTPROC.toString(), true);
		final List<Feature> inputFeatures = Arrays.asList(mdpReqPreprocFeature, mdpResPostprocFeature);
		final List<Feature> outputFeatures = testDomainHelper.updateAndGetPdpFeatures(inputFeatures);
		boolean mdpFeatureFound = false;
		final Set<String> features = new HashSet<>();
		for (final Feature outputFeature : outputFeatures)
		{
			final String featureID = outputFeature.getValue();
			assertTrue(features.add(featureID), "Duplicate feature: " + featureID);
			if (outputFeature.getType().equals(FlatFileBasedDomainsDao.PdpFeatureType.RESULT_POSTPROC.toString()))
			{
				if (featureID.equals(TestCombinedDecisionXacmlJaxbResultPostprocessor.Factory.ID))
				{
					assertTrue(outputFeature.isEnabled(), "Returned feature (" + TestCombinedDecisionXacmlJaxbResultPostprocessor.Factory.ID + ") disabled!");
					mdpFeatureFound = true;
				}
				// else
				// {
				// // another resultPostproc. Must not be enabled. Beware there may be XACML/JSON result postprocs as well
				// assertFalse(outputFeature.isEnabled(), "Returned other resultPostproc (not MDP) feature enabled! (" + featureID + ")");
				// }
			}
		}

		assertTrue(mdpFeatureFound, "No enabled feature '" + TestCombinedDecisionXacmlJaxbResultPostprocessor.Factory.ID + "' found");

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final List<InOutProcChain> ioProcChains = testDomainHelper.getPdpConfFromFile().getIoProcChains();
			final String resultPostprocId = ioProcChains.isEmpty() ? null : ioProcChains.get(0).getResultPostproc();
			assertEquals(resultPostprocId, TestCombinedDecisionXacmlJaxbResultPostprocessor.Factory.ID, "Result postprocessor in PDP conf file does not match features " + inputFeatures);
		}
	}

	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "enableMDPResultPostproc" })
	public void disableMDPResultPostproc(@Optional final String remoteAppBaseUrl) throws JAXBException
	{
		final Feature mdpFeature = new Feature(TestCombinedDecisionXacmlJaxbResultPostprocessor.Factory.ID, PdpFeatureType.RESULT_POSTPROC.toString(), false);
		final List<Feature> inputFeatures = Collections.singletonList(mdpFeature);
		final List<Feature> outputFeatures = testDomainHelper.updateAndGetPdpFeatures(inputFeatures);
		boolean mdpFeatureFound = false;
		final Set<String> features = new HashSet<>();
		for (final Feature outputFeature : outputFeatures)
		{
			final String featureID = outputFeature.getValue();
			assertTrue(features.add(featureID), "Duplicate feature: " + featureID);
			if (outputFeature.getType().equals(PdpFeatureType.RESULT_POSTPROC.toString()))
			{
				/*
				 * Beware there may be other (e.g. XACML/JSON) result postprocs
				 */
				if (featureID.equals(TestCombinedDecisionXacmlJaxbResultPostprocessor.Factory.ID))
				{
					assertFalse(outputFeature.isEnabled(), "Returned feature (" + TestCombinedDecisionXacmlJaxbResultPostprocessor.Factory.ID + ") enabled!");
					mdpFeatureFound = true;
				}
			}
		}

		assertTrue(mdpFeatureFound, "No feature '" + TestCombinedDecisionXacmlJaxbResultPostprocessor.Factory.ID + "' found");

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final List<InOutProcChain> ioProcChains = testDomainHelper.getPdpConfFromFile().getIoProcChains();
			final String resultPostprocId = ioProcChains.isEmpty() ? null : ioProcChains.get(0).getResultPostproc();
			Assert.assertNull(resultPostprocId, "Result postprocessor ('" + resultPostprocId + "') != null in PDP configuration file, therefore does not match features " + inputFeatures);
		}
	}

	/**
	 * Test enable/disable a PDP request-preproc feature: Multiple Decision Request Profile / repeated attribute categories
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableMDPRequestPreproc(@Optional final String remoteAppBaseUrl) throws JAXBException
	{
		final Feature mdpFeature = new Feature(MultiDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID, PdpFeatureType.REQUEST_PREPROC.toString(), true);
		final List<Feature> inputFeatures = Collections.singletonList(mdpFeature);
		final List<Feature> outputFeatures = testDomainHelper.updateAndGetPdpFeatures(inputFeatures);
		boolean mdpFeatureFound = false;
		final Set<String> features = new HashSet<>();
		for (final Feature outputFeature : outputFeatures)
		{
			final String featureID = outputFeature.getValue();
			assertTrue(features.add(featureID), "Duplicate feature: " + featureID);
			if (outputFeature.getType().equals(FlatFileBasedDomainsDao.PdpFeatureType.REQUEST_PREPROC.toString()))
			{
				if (featureID.equals(MultiDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID))
				{
					assertTrue(outputFeature.isEnabled(), "Returned feature (" + MultiDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID + ") disabled!");
					mdpFeatureFound = true;
				}
				else if (featureID.equals(SingleDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID) || featureID.equals(SingleDecisionXacmlJaxbRequestPreprocessor.StrictVariantFactory.ID)
				        || featureID.equals(MultiDecisionXacmlJaxbRequestPreprocessor.StrictVariantFactory.ID))
				{
					/*
					 * Another XACML/XML requestPreproc. Must not be enabled. Beware there may be XACML/JSON requestPreprocs as well.
					 */
					assertFalse(outputFeature.isEnabled(), "Returned other XACML/XML requestPreproc (not MDP) feature enabled! (" + featureID + ")");
				}
			}
		}

		assertTrue(mdpFeatureFound, "No enabled feature '" + MultiDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID + "' found");

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final List<InOutProcChain> ioProcChains = testDomainHelper.getPdpConfFromFile().getIoProcChains();
			final String requestPreprocId = ioProcChains.isEmpty() ? null : ioProcChains.get(0).getRequestPreproc();
			assertEquals(requestPreprocId, MultiDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID, "Request preprocessor in PDP conf file does not match features " + inputFeatures);
		}
	}

	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "enableMDPRequestPreproc" })
	public void disableMDPRequestPreproc(@Optional final String remoteAppBaseUrl) throws JAXBException
	{
		final Feature mdpLaxFeature = new Feature(MultiDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID, PdpFeatureType.REQUEST_PREPROC.toString(), false);
		final Feature mdpStrictFeature = new Feature(MultiDecisionXacmlJaxbRequestPreprocessor.StrictVariantFactory.ID, PdpFeatureType.REQUEST_PREPROC.toString(), false);
		final List<Feature> inputFeatures = Arrays.asList(mdpLaxFeature, mdpStrictFeature);
		final List<Feature> outputFeatures = testDomainHelper.updateAndGetPdpFeatures(inputFeatures);
		boolean mdpFeatureFound = false;
		final Set<String> features = new HashSet<>();
		for (final Feature outputFeature : outputFeatures)
		{
			final String featureID = outputFeature.getValue();
			assertTrue(features.add(featureID), "Duplicate feature: " + featureID);
			if (outputFeature.getType().equals(FlatFileBasedDomainsDao.PdpFeatureType.REQUEST_PREPROC.toString()))
			{
				/*
				 * Beware that there may be also the XACML/JSON request preprocs. We only deal with the XACML/XML ones here.
				 */
				switch (featureID)
				{
					case SingleDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID:
						assertTrue(outputFeature.isEnabled(), "Returned default requestPreproc feature disabled! (" + featureID + ")");
						break;
					case SingleDecisionXacmlJaxbRequestPreprocessor.StrictVariantFactory.ID:
						// another requestPreproc. Must not be enabled.
						assertFalse(outputFeature.isEnabled(), "Returned non-default XACML/XML requestPreproc feature enabled! (" + featureID + ")");
						break;
					case MultiDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID:
					case MultiDecisionXacmlJaxbRequestPreprocessor.StrictVariantFactory.ID:
						assertFalse(outputFeature.isEnabled(), "Returned XACML/XML MDP requestPreproc feature enabled! (" + featureID + ")");
						mdpFeatureFound = true;
						break;
				}
			}
		}

		assertTrue(mdpFeatureFound, "No feature '" + MultiDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID + "' found");

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			final List<InOutProcChain> ioProcChains = testDomainHelper.getPdpConfFromFile().getIoProcChains();
			final String requestPreprocId = ioProcChains.isEmpty() ? null : ioProcChains.get(0).getRequestPreproc();
			/*
			 * If null, it's like having just the default (implicitly enabled always), else make sure it is the default.
			 */
			if (requestPreprocId != null)
			{
				assertEquals(requestPreprocId, SingleDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID, "Request preprocessor in PDP conf file does not match features " + inputFeatures);
			}
		}
	}

	@Test
	public void requestPDPDumb()
	{
		/*
		 * This test is mostly for enablePdpOnly=true
		 */
		final Request xacmlReq = new Request(new RequestDefaults(XPathVersion.V2_0.getURI()),
		        Collections.singletonList(new Attributes(null, Collections.emptyList(), XacmlAttributeCategory.XACML_1_0_ACCESS_SUBJECT.value(), null)), null, false, false);
		testDomain.getPdpResource().requestPolicyDecision(xacmlReq);
	}

	@Parameters({ "useJSON" })
	@Test(dependsOnMethods = { "requestPDPDumb" })
	public void requestXacmlJsonPDPDumb(@Optional final Boolean useJSON) throws Exception
	{
		if (!useJSON)
		{
			return;
		}

		final File testDir = new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp/default_features/xml/IIA001(PolicySet)");
		/*
		 * This test is mostly for enablePdpOnly=true
		 */
		testDomainHelper.requestXacmlJsonPDP(testDir, Collections.emptyList(), !IS_EMBEDDED_SERVER_STARTED.get(), httpClient);
	}

	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" }, dataProvider = "pdpTestFiles")
	public void requestPDPWithoutMDP(final File testDirectory) throws Exception
	{
		// disable all features (incl. MDP) of PDP
		testDomainHelper.requestXacmlXmlPDP(testDirectory, Collections.emptyList(), !IS_EMBEDDED_SERVER_STARTED.get(), java.util.Optional.empty(), enableFastInfoset);
	}

	@Test(dependsOnMethods = { "enableMDPRequestPreproc" })
	public void requestPDPWithMDP() throws Exception
	{
		// enable MDP on PDP
		final Feature mdpFeature = new Feature(MultiDecisionXacmlJaxbRequestPreprocessor.LaxVariantFactory.ID, FlatFileBasedDomainsDao.PdpFeatureType.REQUEST_PREPROC.toString(), true);
		final List<Feature> inputFeatures = Collections.singletonList(mdpFeature);
		final File testDirectory = new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp/mdp/IIIE302(PolicySet)");
		testDomainHelper.requestXacmlXmlPDP(testDirectory, inputFeatures, !IS_EMBEDDED_SERVER_STARTED.get(), java.util.Optional.empty(), enableFastInfoset);
	}

	private void verifySyncAfterPdpConfFileModification(final IdReferenceType newRootPolicyRef) throws JAXBException
	{
		// check PDP returned policy identifier
		final JAXBElement<Request> jaxbXacmlReq = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_IIIG301_PDP_TEST_DIR, RestServiceTest.REQUEST_FILENAME), Request.class);
		final Response actualResponse = testDomain.getPdpResource().requestPolicyDecision(jaxbXacmlReq.getValue());
		for (final JAXBElement<IdReferenceType> jaxbElt : actualResponse.getResults().get(0).getPolicyIdentifierList().getPolicyIdReferencesAndPolicySetIdReferences())
		{
			final String tagLocalName = jaxbElt.getName().getLocalPart();
			if (tagLocalName.equals("PolicySetIdReference"))
			{
				assertEquals(jaxbElt.getValue(), newRootPolicyRef,
				        "Manual sync with API getOtherPdpProperties() failed: PolicySetIdReference returned by PDP does not match the root policyRef in PDP configuration file");
				return;
			}
		}

		fail("Manual sync with API getOtherPdpProperties() failed: PolicySetIdReference returned by PDP does not match the root policyRef in PDP configuration file");
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void headRootPolicyRefAfterChangingPdpConfFile(@Optional final String remoteAppBaseUrl, @Optional("false") final Boolean isFilesystemLegacy) throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final IdReferenceType newRootPolicyRef = testDomainHelper.modifyRootPolicyRefInPdpConfFile(isFilesystemLegacy);

		// Manual sync via HEAD /domains/{domainId}/pap/properties
		final javax.ws.rs.core.Response response = httpClient.reset().path("domains").path(testDomainId).path("pap").path("pdp.properties").accept(MediaType.APPLICATION_XML).head();
		assertEquals(response.getStatus(), javax.ws.rs.core.Response.Status.OK.getStatusCode(), "HEAD /domains/" + testDomainId + "/pap/pdp.properties failed");

		verifySyncAfterPdpConfFileModification(newRootPolicyRef);
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void getRootPolicyRefAfterChangingPdpConfFile(@Optional final String remoteAppBaseUrl, @Optional("false") final Boolean isFilesystemLegacy) throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final IdReferenceType newRootPolicyRef = testDomainHelper.modifyRootPolicyRefInPdpConfFile(isFilesystemLegacy);

		// Manual sync via GET /domains/{domainId}/pap/properties
		final long timeBeforeSync = System.currentTimeMillis();
		final PdpProperties pdpProps = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties();
		final long lastModifiedTime = pdpProps.getLastModifiedTime().toGregorianCalendar().getTimeInMillis();
		assertTrue(lastModifiedTime >= timeBeforeSync, "Manual sync with API getOtherPdpProperties() failed: returned lastModifiedTime is not up-to-date");

		assertEquals(pdpProps.getApplicablePolicies().getRootPolicyRef(), newRootPolicyRef,
		        "Manual sync with API getOtherPdpProperties() failed: returned root policyRef does not match the one in PDP configuration file");

		verifySyncAfterPdpConfFileModification(newRootPolicyRef);
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "setRootPolicyWithCircularRef", "getRootPolicyRefAfterChangingPdpConfFile" }, expectedExceptions = BadRequestException.class)
	public void setRootPolicyWithTooDeepPolicyRef(@Optional final String remoteAppBaseUrl, @Optional("false") final Boolean isFilesystemLegacy) throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		// set PDP configuration locally to maxPolicyRefDepth = 2
		testDomainHelper.modifyMaxPolicyRefDepthInPdpConfFile(isFilesystemLegacy, 2);

		// add refPolicies
		final JAXBElement<PolicySet> refPolicySet = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.TooDeep/refPolicies/pps-employee.xml"),
		        PolicySet.class);
		testDomainHelper.testAddAndGetPolicy(refPolicySet.getValue());
		final JAXBElement<PolicySet> refPolicySet2 = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.TooDeep/refPolicies/pps-manager.xml"),
		        PolicySet.class);
		testDomainHelper.testAddAndGetPolicy(refPolicySet2.getValue());

		// Then attempt to put bad root policy set (referenced policysets in
		// Policy(Set)IdReferences do
		// not exist since refPolicySets empty)
		final JAXBElement<PolicySet> policySetWithRefs = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.TooDeep/policy.xml"), PolicySet.class);
		testDomainHelper.setRootPolicy(policySetWithRefs.getValue(), true);
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void getPdpPropertiesAfterModifyingUsedPolicyDirectory(@Optional final String remoteAppBaseUrl, @Optional("false") final Boolean isFileSystemLegacy)
	        throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final File inputRefPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_REF_POLICIES_DIRECTORY_NAME + "/pps-employee.xml");
		final File inputRootPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_DEFAULT_POLICYSET_FILENAME);
		final IdReferenceType newRefPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, false, isFileSystemLegacy);

		// Manual sync of PDP's active policies via GET /domains/{domainId}/pap/properties
		final long timeBeforeSync = System.currentTimeMillis();
		final PdpProperties pdpProps = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties();
		final long timeAfterSync = System.currentTimeMillis();

		// check new PDP lastModifiedTime
		final long lastModifiedTime = pdpProps.getLastModifiedTime().toGregorianCalendar().getTimeInMillis();
		if (LOGGER.isDebugEnabled())
		{
			LOGGER.debug("Domain '{}': PDP lastmodifiedtime = {}", testDomainId, RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(lastModifiedTime)));
		}

		assertTrue(lastModifiedTime >= timeBeforeSync && lastModifiedTime <= timeAfterSync, "Manual sync with API Domain('" + testDomainId + "')#getOtherPdpProperties() failed: lastModifiedTime ("
		        + RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(lastModifiedTime))
		        + ") returned by getOtherPdpProperties() does not match the time when getPolicies() was called. Expected to be in range ["
		        + RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(timeBeforeSync)) + ", " + RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(timeAfterSync)) + "]");
		// check enabled policies
		assertTrue(pdpProps.getApplicablePolicies().getRefPolicyReves().contains(newRefPolicySetRef),
		        "Manual sync with API Domain('" + testDomainId + "')#getOtherPdpProperties() failed: <refPolicyRef>s returned by getOtherPdpProperties() ( = "
		                + pdpProps.getApplicablePolicies().getRefPolicyReves() + ") does not contain last applicable refpolicy version created on disk: " + newRefPolicySetRef);

		// Redo the same but updating the root policy version on disk this time
		final IdReferenceType newRootPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, true, isFileSystemLegacy);

		// Manual sync of PDP's active policies via GET /domains/{domainId}/pap/properties
		final long timeBeforeSync2 = System.currentTimeMillis();
		final PdpProperties pdpProps2 = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties();
		final long timeAfterSync2 = System.currentTimeMillis();
		final long lastModifiedTime2 = pdpProps2.getLastModifiedTime().toGregorianCalendar().getTimeInMillis();
		if (LOGGER.isDebugEnabled())
		{
			LOGGER.debug("Domain '{}': PDP lastmodifiedtime = {}", testDomainId, RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(lastModifiedTime2)));
		}

		assertTrue(lastModifiedTime2 >= timeBeforeSync2 && lastModifiedTime2 <= timeAfterSync2, "Manual sync with API Domain('" + testDomainId + "')#getOtherPdpProperties() failed: lastModifiedTime ("
		        + RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(lastModifiedTime2))
		        + ") returned by getOtherPdpProperties() does not match the time when getPolicies() was called. Expected to be in range ["
		        + RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(timeBeforeSync2)) + ", " + RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(timeAfterSync2)) + "]");
		// check enabled policies
		assertEquals(pdpProps2.getApplicablePolicies().getRootPolicyRef(), newRootPolicySetRef,
		        "Manual sync with API Domain('" + testDomainId + "')#getOtherPdpProperties() failed: rootPolicyRef returned by getOtherPdpProperties() ( = "
		                + pdpProps2.getApplicablePolicies().getRootPolicyRef() + ") does not match last applicable root policy version created on disk: " + newRootPolicySetRef);
	}

	private void verifyPdpReturnedPolicies(final IdReferenceType expectedPolicyRef) throws JAXBException
	{
		/*
		 * We cannot check again with /domain/{domainId}/pap/properties because it will sync again and this is not what we intend to test here.
		 */
		// Check PDP returned policy identifiers
		final JAXBElement<Request> jaxbXacmlReq = testDomainHelper.unmarshalXacml(new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, "requestPolicyIdentifiers.xml"), Request.class);
		final Request xacmlReq = jaxbXacmlReq.getValue();
		final Response actualResponse = testDomain.getPdpResource().requestPolicyDecision(xacmlReq);
		boolean isNewRefPolicyRefMatched = false;
		final List<JAXBElement<IdReferenceType>> jaxbPolicyRefs = actualResponse.getResults().get(0).getPolicyIdentifierList().getPolicyIdReferencesAndPolicySetIdReferences();
		final List<IdReferenceType> returnedPolicyIdentifiers = new ArrayList<>();
		for (final JAXBElement<IdReferenceType> jaxbPolicyRef : jaxbPolicyRefs)
		{
			final String tagLocalName = jaxbPolicyRef.getName().getLocalPart();
			if (tagLocalName.equals("PolicySetIdReference"))
			{
				final IdReferenceType idRef = jaxbPolicyRef.getValue();
				returnedPolicyIdentifiers.add(idRef);
				if (idRef.equals(expectedPolicyRef))
				{
					isNewRefPolicyRefMatched = true;
				}
			}
		}

		assertTrue(isNewRefPolicyRefMatched, "Manual sync with API Domain('" + testDomainId + "')#getPolicies() failed: new policy version created on disk (" + expectedPolicyRef
		        + ") is not in PolicySetIdReferences returned by PDP: " + returnedPolicyIdentifiers);

	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "getPdpPropertiesAfterModifyingUsedPolicyDirectory" })
	public void headPdpPropertiesAfterModifyingUsedPolicyDirectory(@Optional final String remoteAppBaseUrl, @Optional("false") final Boolean isFileSystemLegacy)
	        throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final File inputRefPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_REF_POLICIES_DIRECTORY_NAME + "/pps-employee.xml");
		final File inputRootPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_DEFAULT_POLICYSET_FILENAME);
		final IdReferenceType newRefPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, false, isFileSystemLegacy);

		// Manual sync via HEAD /domains/{domainId}/pap/properties
		final javax.ws.rs.core.Response response = httpClient.reset().path("domains").path(testDomainId).path("pap").path("pdp.properties").accept(MediaType.APPLICATION_XML).head();
		assertEquals(response.getStatus(), javax.ws.rs.core.Response.Status.OK.getStatusCode(), "HEAD /domains/" + testDomainId + "/pap/pdp.properties failed");

		verifyPdpReturnedPolicies(newRefPolicySetRef);

		// Redo the same but adding new root policy version on disk this time
		final IdReferenceType newRootPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, true, isFileSystemLegacy);

		// Manual sync of PDP's active policies via GET /domains/{domainId}/pap/policies
		testDomain.getPapResource().getPoliciesResource().getPolicies().getLinks();

		verifyPdpReturnedPolicies(newRootPolicySetRef);
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void getPoliciesAfterModifyingUsedPolicyDirectory(@Optional final String remoteAppBaseUrl, @Optional("false") final Boolean isFilesystemLegacy) throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final File inputRefPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_REF_POLICIES_DIRECTORY_NAME + "/pps-employee.xml");
		final File inputRootPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_DEFAULT_POLICYSET_FILENAME);
		final IdReferenceType newRefPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, false, isFilesystemLegacy);

		// Manual sync of PDP's active policies via GET /domains/{domainId}/pap/policies
		testDomain.getPapResource().getPoliciesResource().getPolicies().getLinks();

		verifyPdpReturnedPolicies(newRefPolicySetRef);

		// Redo the same but adding new root policy version on disk this time
		final IdReferenceType newRootPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, true, isFilesystemLegacy);

		// Manual sync of PDP's active policies via GET /domains/{domainId}/pap/policies
		testDomain.getPapResource().getPoliciesResource().getPolicies().getLinks();

		verifyPdpReturnedPolicies(newRootPolicySetRef);
	}

	/**
	 * Test for issue https://github.com/authzforce/server/issues/66
	 */
	@Parameters({ "useJSON" })
	@Test(dependsOnMethods = {"requestPDPWithoutMDP", "enableThenDisableXPath" })
	public void issueGH66_AttributeSelector_Path_with_xmlns_prefix(@Optional final Boolean useJSON) throws Exception
	{
		if(useJSON) {
			// Does not apply to JSON Profile, for XML only
			return;
		}

		// XPath feature required
		final Feature xpathFeature = new Feature(PdpCoreFeature.XPATH_EVAL.toString(), PdpFeatureType.CORE.toString(), true);
		final File testDir = new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp/xpath/GH-66_AttributeSelector_xmlns_prefix");

		// Pass httpClient to send raw XACML Policy (as String) instead of JAXB value because it is a XPath test (AttributeSelector) with extra namespace prefix-URIs only used in XPATH expressions (not in XML schema), therefore would be lost when unmarshalled to JAXB.
		testDomainHelper.requestXacmlXmlPDP(testDir, Collections.singletonList(xpathFeature), !IS_EMBEDDED_SERVER_STARTED.get(), java.util.Optional.of(httpClient), enableFastInfoset);
	}
}
