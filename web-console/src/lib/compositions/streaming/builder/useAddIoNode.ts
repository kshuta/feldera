// Logic to add either a input or output node to the graph.

import { randomString } from '$lib/functions/common/string'
import { AttachedConnector, ProgramSchema } from '$lib/services/manager'
import { ConnectorDescr } from '$lib/services/manager/models/ConnectorDescr'
import { useCallback } from 'react'
import { getConnectedEdges, useReactFlow } from 'reactflow'

import { useRedoLayout } from './useAutoLayout'

const HEIGHT_OFFSET = 120

// Checks if the connector can connect to a give schema
export function connectorConnects(schema: ProgramSchema | null | undefined, ac: AttachedConnector): boolean {
  if (!schema) {
    return false
  }
  return (ac.is_input ? schema.inputs : schema.outputs).some(view => view.name === ac.relation_name)
}

export function useAddConnector() {
  const { setNodes, getNodes, getNode, addNodes, addEdges } = useReactFlow()
  const redoLayout = useRedoLayout()

  const addNewConnector = useCallback(
    (connector: ConnectorDescr, ac: AttachedConnector) => {
      // Input or Output?
      const newNodeType = ac.is_input ? 'inputNode' : 'outputNode'
      const placeholderId = ac.is_input ? 'inputPlaceholder' : 'outputPlaceholder'
      const placeholder = getNode(placeholderId)
      if (!placeholder) {
        return
      }

      // If this node already exists, don't add it again
      const existingNode = getNodes().find(node => node.id === ac.name)
      if (existingNode) {
        return
      }

      // Move the placeholder node down a bit; useAutoLayout will eventually
      // also place it at the right spot, but it looks better when it happens
      // here immediately.
      setNodes(nodes =>
        nodes.map(node => {
          if (node.id === placeholderId) {
            return {
              ...node,
              position: { x: placeholder.position.x, y: placeholder.position.y + HEIGHT_OFFSET }
            }
          }

          return node
        })
      )

      // Add the new nodes
      addNodes({
        position: { x: placeholder.position.x, y: placeholder.position.y },
        id: ac.name,
        type: newNodeType,
        deletable: true,
        data: { connector, ac }
      })
      redoLayout()

      // Now that we have the node, we need to add a connector if we have one
      const sqlNode = getNode('sql')
      const ourNode = getNode(ac.name)
      const tableOrView = ac.relation_name
      const sqlPrefix = ac.is_input ? 'table-' : 'view-'
      const connectorHandle = sqlPrefix + tableOrView
      const hasAnEdge = ac.relation_name != ''

      if (!(hasAnEdge && sqlNode && ourNode)) {
        return
      }

      const existingEdge = getConnectedEdges([sqlNode, ourNode], []).find(
        edge => edge.targetHandle === connectorHandle || edge.sourceHandle === connectorHandle
      )

      if (existingEdge) {
        return
      }

      const sourceId = ac.is_input ? ac.name : 'sql'
      const targetId = ac.is_input ? 'sql' : ac.name
      const sourceHandle = ac.is_input ? null : connectorHandle
      const targetHandle = ac.is_input ? connectorHandle : null

      addEdges({
        id: randomString(),
        source: sourceId,
        target: targetId,
        sourceHandle: sourceHandle,
        targetHandle: targetHandle
      })
    },
    [getNode, getNodes, setNodes, addNodes, addEdges, redoLayout]
  )

  return addNewConnector
}