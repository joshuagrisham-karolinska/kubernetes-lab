<?xml version="1.0"?>
<xsl:transform version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

<xsl:output method="text"/>
<xsl:strip-space elements="*"/>

<xsl:template match="/">
{
  <xsl:call-template name="term_names">
    <xsl:with-param name="prefix">template</xsl:with-param>
    <xsl:with-param name="nodes" select="/*[local-name()='template']/*[local-name()='definition']/*[local-name()='term_definitions']"/>
  </xsl:call-template>

  <xsl:call-template name="term_names">
    <xsl:with-param name="prefix">careunit</xsl:with-param>
    <xsl:with-param name="nodes" select="//*[local-name()='attributes'][*[local-name()='rm_attribute_name'][text()='other_context']]/*[local-name()='children'][*[local-name()='rm_type_name'][text()='ITEM_TREE']]/*[local-name()='attributes'][*[local-name()='rm_attribute_name'][text()='items']]/*[local-name()='children'][*[local-name()='node_id'][text()='at0000'] and *[local-name()='rm_type_name'][text()='CLUSTER']]/*[local-name()='term_definitions']"/>
  </xsl:call-template>

  <xsl:call-template name="term_names">
    <xsl:with-param name="prefix">careunit_careprovider</xsl:with-param>
    <xsl:with-param name="nodes" select="//*[local-name()='attributes'][*[local-name()='rm_attribute_name'][text()='other_context']]/*[local-name()='children'][*[local-name()='rm_type_name'][text()='ITEM_TREE']]/*[local-name()='attributes'][*[local-name()='rm_attribute_name'][text()='items']]/*[local-name()='children'][*[local-name()='node_id'][text()='at0000'] and *[local-name()='rm_type_name'][text()='CLUSTER']]/*[local-name()='attributes'][*[local-name()='rm_attribute_name'][text()='items']]/*[local-name()='children'][*[local-name()='rm_type_name'][text()='CLUSTER']]/*[local-name()='term_definitions']"/>
  </xsl:call-template>

  "organization_cluster_archetype_id": "<xsl:value-of select="//*[local-name()='attributes'][*[local-name()='rm_attribute_name'][text()='other_context']]/*[local-name()='children'][*[local-name()='rm_type_name'][text()='ITEM_TREE']]/*[local-name()='attributes'][*[local-name()='rm_attribute_name'][text()='items']]/*[local-name()='children'][*[local-name()='node_id'][text()='at0000'] and *[local-name()='rm_type_name'][text()='CLUSTER']]/*[local-name()='archetype_id']/*[local-name()='value']/text()"/>",
  "template_other_context_tree_node_id": "<xsl:value-of select="//*[local-name()='attributes'][*[local-name()='rm_attribute_name'][text()='other_context']]/*[local-name()='children'][*[local-name()='rm_type_name'][text()='ITEM_TREE']]/*[local-name()='node_id']/text()"/>",
  "rm_version": "1.0.4"
}
</xsl:template>

<xsl:template name="term_names">
  <xsl:param name="prefix"></xsl:param>
  <xsl:param name="nodes"></xsl:param>
  <xsl:for-each select="$nodes">
  "<xsl:value-of select="$prefix"/>_<xsl:value-of select="@code"/>_name": "<xsl:value-of select="*[local-name()='items'][@id='text']/text()"/>",
  </xsl:for-each>
</xsl:template>

</xsl:transform>
