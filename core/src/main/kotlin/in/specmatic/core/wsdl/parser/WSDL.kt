package `in`.specmatic.core.wsdl.parser

import `in`.specmatic.core.SPECMATIC_GITHUB_ISSUES
import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.core.value.*
import `in`.specmatic.core.wsdl.parser.message.*
import java.io.File

private fun getXmlnsDefinitions(wsdlNode: XMLNode): Map<String, String> {
    return wsdlNode.attributes.filterKeys {
        it.startsWith("xmlns:")
    }.mapValues {
        it.value.toStringValue()
    }.map {
        Pair(it.value, it.key.removePrefix("xmlns:"))
    }.toMap()
}

private fun definitionsFrom(rootDefinitionXML: XMLNode): List<XMLNode> {
    val importedDefinitionXMLs = rootDefinitionXML.findChildrenByName("import").filter {
        it.attributes.containsKey("location")
    }.map { importTag ->
        val wsdlFilename = importTag.getAttributeValue("location")
        val definition = toXMLNode(File(wsdlFilename).readText())
        val subDefinitions = definitionsFrom(definition)
        listOf(definition).plus(subDefinitions)
    }.flatten()

    return listOf(rootDefinitionXML).plus(importedDefinitionXMLs)
}

fun getSchemaNodesFromDefinition(definition: XMLNode): List<XMLNode> {
    val typesNode = definition.findFirstChildByName("types") ?: return emptyList()
    val schemasWithinDefinition =  typesNode.findChildrenByName("schema")

    val importedSchemas = schemasWithinDefinition.map { schema ->
        loadSchemaImports(schema)
    }.flatten()

    return schemasWithinDefinition.plus(importedSchemas)
}

fun loadSchemaImports(schema: XMLNode): List<XMLNode> {
    val importNodes = schema.findChildrenByName("import").filter { it.attributes.containsKey("schemaLocation") }

    return importNodes.map { importNode ->
        val filename = importNode.getAttributeValue("schemaLocation")
        val importedSchema = toXMLNode(File(filename).readText())
        listOf(importedSchema).plus(loadSchemaImports(importedSchema))
    }.flatten()
}

private fun schemasFrom(definition: XMLNode): Map<String, XMLNode> {
    val schemas = getSchemaNodesFromDefinition(definition)

    return schemas.map { schema ->
        Pair(schema.getAttributeValue("targetNamespace"), schema)
    }.toMap()
}

fun WSDL(rootDefinition: XMLNode): WSDL {
    val definitions = definitionsFrom(rootDefinition).map { definition ->
        Pair(definition.getAttributeValue("targetNamespace"), definition)
    }.toMap()

    val schemas: Map<String, XMLNode> = listOf(rootDefinition).plus(definitions.values).map { definition ->
        schemasFrom(definition)
    }.fold(emptyMap()) { accumulatedSchemas, schema ->
        accumulatedSchemas.plus(schema)
    }

    val populatedSchemas = addSchemasToNodes(schemas)

    val typesNode = rootDefinition.findFirstChildByName("types") ?: toXMLNode("<types/>")

    return WSDL (rootDefinition, definitions, populatedSchemas, typesNode, getXmlnsDefinitions(rootDefinition))
}

fun addSchemasToNodes(schemas: Map<String, XMLNode>): Map<String, XMLNode> {
    return schemas.mapValues { (_, schema) ->
        schema.copy(childNodes = schema.childNodes.map { it.addSchema(schema) })
    }
}

data class WSDL(private val rootDefinition: XMLNode, val definitions: Map<String, XMLNode>, val schemas: Map<String, XMLNode>, private val typesNode: XMLNode, val namespaceToPrefix: Map<String, String>) {
    fun getServiceName() =
        rootDefinition.findFirstChildByName("service")?.attributes?.get("name")
            ?: throw ContractException("Couldn't find attribute name in node service")

    fun getPortType(): XMLNode {
        val binding = getBinding()
        val portTypeQName = binding.getAttributeValue("type")

        return findInDefinition("portType", binding, portTypeQName)
    }

    fun getBinding(): XMLNode {
        val servicePort = getServicePort()
        val bindingQName = servicePort.getAttributeValue("binding")

        return findInDefinition("binding", servicePort, bindingQName)
    }

    private fun findInDefinition(
        tagName: String,
        node: XMLNode,
        qname: String
    ): XMLNode {
        val namespace = node.resolveNamespace(qname)
        val localName = qname.localName()

        val definition = definitions[namespace]
            ?: throw ContractException("Tried to lookup $tagName named $qname, resolved namespace prefix to $namespace, but could not find a definition with that namespace")
        return definition.findByNodeNameAndAttribute(tagName, "name", localName)
    }

    fun getServicePort() = rootDefinition.getXMLNodeByPath("service.port")

    fun getNamespaces(typeInfo: WSDLTypeInfo): Map<String, String> {
        return typeInfo.getNamespaces(rootDefinition.attributes)
    }

    fun getNamespaces(): Map<String, String> {
        return namespaceToPrefix.entries.map {
            Pair(it.value, it.key)
        }.toMap()
    }

    fun mapNamespaceToPrefix(targetNamespace: String): String {
        return namespaceToPrefix[targetNamespace] ?: throw ContractException("The target namespace $targetNamespace was not found in the WSDL definitions tag.")
    }

    val operations: List<XMLNode>
        get() {
        return getBinding().findChildrenByName("operation")
    }

    fun convertToGherkin(): String {
        val port = rootDefinition.getXMLNodeOrNull("service.port")
        val endpoint = rootDefinition.getXMLNodeOrNull("service.endpoint")

        val (url, soapParser) = when {
            port != null -> Pair(port.getAttributeValueAtPath("address", "location"), SOAP11Parser(this))
            endpoint != null -> Pair(endpoint.getAttributeValueAtPath("address", "location"), SOAP20Parser())
            else -> throw ContractException("Could not find the service endpoint")
        }

        return soapParser.convertToGherkin(url)
    }

    fun findComplexType(
        element: XMLNode,
        attributeName: String
    ): XMLNode {
        val fullTypeName = element.attributes.getValue(attributeName).toStringValue()
        val schema = findSchema(namespace(fullTypeName, element))
        return schema.findByNodeNameAndAttribute("complexType", "name", fullTypeName.localName())
    }

    fun findTypeFromAttribute(
        element: XMLNode,
        attributeName: String
    ): XMLNode {
        val fullTypeName = element.attributes.getValue(attributeName).toStringValue()
        return findElement(fullTypeName.localName(), namespace(fullTypeName, element))
    }

    private fun namespace(fullTypeName: String, element: XMLNode): String {
        val namespacePrefix = fullTypeName.namespacePrefix()
        return if (namespacePrefix.isBlank())
            ""
        else
            element.namespaces[namespacePrefix]
                ?: throw ContractException("Could not find namespace with prefix $namespacePrefix in xml node $element")
    }

    fun findElement(typeName: String, namespace: String): XMLNode {
        val schema = findSchema(namespace)

        return schema.getXMLNodeByAttributeValue("name", typeName)
    }

    fun getSOAPElement(fullyQualifiedName: FullyQualifiedName): WSDLElement {
        val schema = findSchema(fullyQualifiedName.namespace)

        val node = schema.getXMLNodeByAttributeValue("name", fullyQualifiedName.localName)

        return if(hasSimpleTypeAttribute(node)) {
            SimpleElement(fullyQualifiedName.qname, node, this)
        } else {
            ComplexElement(fullyQualifiedName.qname, node, this)
        }
    }

    fun findSchema(namespace: String): XMLNode {
        if(namespace.isBlank())
            throw ContractException("Cannot look for an empty schema namespace. Please report this to the Specmatic Builders at $SPECMATIC_GITHUB_ISSUES")

        return schemas[namespace]
            ?: throw ContractException("Couldn't find schema with targetNamespace $namespace")
    }

    fun getComplexTypeNode(element: XMLNode): ComplexType {
        val node = when {
            element.attributes.containsKey("type") -> findComplexType(element, "type")
            else -> element.childNodes.filterIsInstance<XMLNode>().filterNot { it.name == "annotation" }.first()
        }.also {
            if (it.name != "complexType")
                throw ContractException("Unexpected type node found\nSource: $element\nType: $it")
        }

        return ComplexType(node, this)
    }

    fun findMessageNode(fullyQualifiedName: FullyQualifiedName): XMLNode {
        val definition = definitions[fullyQualifiedName.namespace]
            ?: throw ContractException("Could not find message named ${fullyQualifiedName.qname}. ${fullyQualifiedName.prefix} mapped to ${fullyQualifiedName.namespace}, but could not find a definition with this targetNamespace.")

        return definition.findByNodeNameAndAttribute(
            "message",
            "name",
            fullyQualifiedName.localName,
            "Message node ${fullyQualifiedName.qname} not found"
        )
    }

    fun mapToNamespacePrefixInDefinitions(namespacePrefix: String, element: XMLNode): String {
        val namespaceValue = element.namespaces[namespacePrefix]
            ?: throw ContractException("Can't find namespace prefix $namespacePrefix for element $element")
        return namespaceToPrefix.getValue(namespaceValue)
    }

    fun getWSDLElementType(parentTypeName: String, node: XMLNode): ChildElementType {
        return when {
            node.attributes.containsKey("ref") -> {
                ElementReference(node, this)
            }
            node.attributes.containsKey("type") -> {
                TypeReference(node, this)
            }
            else -> {
                InlineType(parentTypeName, node, this)
            }
        }
    }

    fun getQualification(element: XMLNode, wsdlTypeReference: String): NamespaceQualification {
        val namespace = element.resolveNamespace(wsdlTypeReference)

        val schema: XMLNode = if(namespace.isBlank())
            element.schema ?: throw ContractException("No type reference to indicate the schema, and the element node ${element.oneLineDescription} did not have a schema attached")
        else
            this.findSchema(namespace)

        val schemaElementFormDefault = schema.attributes["elementFormDefault"]?.toStringValue()
        val elementForm = element.attributes["form"]?.toStringValue()

        return when(elementForm ?: schemaElementFormDefault) {
            "qualified" -> QualifiedNamespace(element, schema, wsdlTypeReference, this)
            else -> UnqualifiedNamespace(element.getAttributeValue("name"))
        }
    }
}
