package com.jtransc.types

import com.jtransc.ast.*
import com.jtransc.ds.cast
import com.jtransc.ds.hasFlag
import com.jtransc.error.InvalidOperationException
import com.jtransc.error.deprecated
import com.jtransc.error.invalidOp
import com.jtransc.error.noImpl
import com.jtransc.input.astRef
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.*

val Handle.ast: AstMethodRef get() = AstMethodRef(FqName.fromInternal(this.owner), this.name, AstType.demangleMethod(this.desc))

//const val DEBUG = true
const val DEBUG = false

fun Asm2Ast(clazz: AstType.REF, method: MethodNode): AstBody {
	val tryCatchBlocks = method.tryCatchBlocks.cast<TryCatchBlockNode>()
	val basicBlocks = BasicBlocks(clazz, method)
	val locals = basicBlocks.locals
	val labels = basicBlocks.labels

	for (b in tryCatchBlocks) {
		labels.ref(labels.label(b.start))
		labels.ref(labels.label(b.end))
		labels.ref(labels.label(b.handler))
		labels.referencedHandlers += b.handler
	}

	val prefix = createFunctionPrefix(clazz, method, locals)
	basicBlocks.queue(method.instructions.first, prefix.output)

	val body2 = method.instructions.toArray().toList().flatMap {
		basicBlocks.getBasicBlockForLabel(it)?.stms ?: listOf()
	}

	return AstBody(
		AstStm.STMS(prefix.stms + body2),
		locals.locals.values.filterIsInstance<AstExpr.LOCAL>().map { it.local },
		tryCatchBlocks.map {
			AstTrap(
				start = labels.label(it.start),
				end = labels.label(it.end),
				handler = labels.label(it.handler),
				exception = if (it.type != null) AstType.REF_INT2(it.type) else AstType.OBJECT
			)
		}
	)
}

data class FunctionPrefix(val output: BasicBlock.Input, val stms: List<AstStm>)

class BasicBlocks(
	private val clazz: AstType.REF,
	private val method: MethodNode
) {
	val locals = Locals()
	val labels = Labels()
	private val blocks = hashMapOf<AbstractInsnNode, BasicBlock>()

	fun queue(entry: AbstractInsnNode, input: BasicBlock.Input) {
		add(BasicBlockBuilder(clazz, method, locals, labels, this).call(entry, input))
	}

	fun add(bb: BasicBlock) {
		blocks[bb.entry] = bb
	}

	fun getBasicBlockForLabel(label: AbstractInsnNode): BasicBlock? {
		return blocks[label]
	}
}


fun createFunctionPrefix(clazz: AstType.REF, method: MethodNode, locals: Locals): FunctionPrefix {
	val localsOutput = arrayListOf<AstExpr.LocalExpr>()
	val isStatic = method.access.hasFlag(Opcodes.ACC_STATIC)
	val methodType = AstType.demangleMethod(method.desc)

	var stms = ArrayList<AstStm>()
	var idx = 0

	for (arg in (if (!isStatic) listOf(AstExpr.THIS(clazz.name)) else listOf()) + methodType.args.map { AstExpr.PARAM(it) }) {
		//setLocalAtIndex(idx, AstExpr.PARAM(arg))
		val local = locals.local(fixType(arg.type), idx)
		stms.add(AstStmUtils.set(local, arg))
		localsOutput += local
		idx++
		if (arg.type.isLongOrDouble()) {
			localsOutput += local
			idx++
		}
	}

	return FunctionPrefix(BasicBlock.Input(Stack(), localsOutput), stms)
}

class BasicBlock(
	val input: Input,
	val entry: AbstractInsnNode,
	val stms: List<AstStm>
) {
	data class Input(val stack: Stack<AstExpr>, val locals: ArrayList<AstExpr.LocalExpr>)
}

class Labels {
	val labels = hashMapOf<LabelNode, AstLabel>()
	val referencedLabels = hashSetOf<AstLabel>()
	val referencedHandlers = hashSetOf<LabelNode>()

	fun label(label: LabelNode): AstLabel {
		if (label !in labels) labels[label] = AstLabel("label_${label.label}")
		return labels[label]!!
	}

	fun ref(label: AstLabel): AstLabel {
		referencedLabels += label
		return label
	}
}

class Locals {
	data class ID(val index: Int, val type: AstType, val prefix: String)

	var tempLocalId = 0
	val locals = hashMapOf<Locals.ID, AstExpr.LocalExpr>()  // @TODO: remove this
	val localsAtIndex = hashMapOf<Int, AstExpr.LocalExpr>()

	fun localPair(index: Int, type: AstType, prefix: String) = Locals.ID(index, fixType(type), prefix)

	fun local(type: AstType, index: Int, prefix: String = "l"): AstExpr.LocalExpr {
		val info = localPair(index, type, prefix)
		//if (info !in locals) locals[info] = AstExpr.LOCAL(AstLocal(index, "local${index}_$type", type))
		val type2 = fixType(type)
		if (info !in locals) locals[info] = AstExpr.LOCAL(AstLocal(index, "$prefix${nameType(type2)}${index}", type2))
		return locals[info]!!
	}

	fun tempLocal(type: AstType): AstExpr.LocalExpr {
		return local(type, tempLocalId++, "t")
	}
}

fun fixType(type: AstType): AstType {
	return if (type is AstType.Primitive) {
		when (type) {
			AstType.INT, AstType.FLOAT, AstType.DOUBLE, AstType.LONG -> type
			else -> AstType.INT
		}
	} else {
		AstType.OBJECT
	}
}

fun nameType(type: AstType): String {
	if (type is AstType.Primitive) {
		return type.chstring
	} else {
		return "A"
	}
}


// http://stackoverflow.com/questions/4324321/java-local-variables-how-do-i-get-a-variable-name-or-type-using-its-index
private class BasicBlockBuilder(
	val clazz: AstType.REF,
	val method: MethodNode,
	val locals: Locals,
	val labels: Labels,
    val basicBlocks: BasicBlocks
) {
	companion object {
		val PTYPES = listOf(AstType.INT, AstType.LONG, AstType.FLOAT, AstType.DOUBLE, AstType.OBJECT, AstType.BYTE, AstType.CHAR, AstType.SHORT)
		val CTYPES = listOf(AstBinop.EQ, AstBinop.NE, AstBinop.LT, AstBinop.GE, AstBinop.GT, AstBinop.LE, AstBinop.EQ, AstBinop.NE)
	}

	val methodRef = method.astRef(clazz)
	//val list = method.instructions
	val methodType = AstType.demangleMethod(method.desc)
	val stms = ArrayList<AstStm>()
	val stack = Stack<AstExpr>()
	var lastLine = -1
	var lastLabel: LabelNode? = null

	//fun fix(field: AstFieldRef): AstFieldRef = locateRightClass.locateRightField(field)
	//fun fix(method: AstMethodRef): AstMethodRef = locateRightClass.locateRightMethod(method)

	fun fix(field: AstFieldRef): AstFieldRef = field
	fun fix(method: AstMethodRef): AstMethodRef = method


	fun getType(value: Any?): AstType {
		return when (value) {
			is Int -> AstType.INT
			is String -> AstType.STRING // Or custom type?
		//else -> AstType.UNKNOWN
			else -> {
				throw InvalidOperationException("$value")
			}
		}
	}


	fun stmAdd(s: AstStm) {
		// Adding statements must dump stack (and restore later) so we preserve calling order!
		// Unless it is just a LValue
		//if (stack.size == 1 && stack.peek() is AstExpr.LocalExpr) {
		//if (false) {
		//	stms.add(s)
		//} else {
		val stack = preserveStack()
		stms.add(s)
		restoreStack(stack)
		//}
	}

	fun stackPush(e: AstExpr) {
		stack.push(e)
	}

	fun stackPushList(e: List<AstExpr>) {
		for (i in e) stackPush(i)
	}

	fun stackPop(): AstExpr {
		if (stack.isEmpty()) {
			println("stack is empty!")
		}
		return stack.pop()
	}

	fun stackPeek(): AstExpr {
		if (stack.isEmpty()) {
			println("stack is empty!")
		}
		return stack.peek()
	}

	fun stmSet(local: AstExpr.LocalExpr, value: AstExpr): Boolean {
		if (local != value) {
			stmAdd(AstStm.SET(local, fastcast(value, local.type)))
			return true
		} else {
			return false
		}
	}

	fun handleField(i: FieldInsnNode) {
		//val isStatic = (i.opcode == Opcodes.GETSTATIC) || (i.opcode == Opcodes.PUTSTATIC)
		val ref = fix(AstFieldRef(AstType.REF_INT2(i.owner).fqname.fqname, i.name, com.jtransc.ast.AstType.demangle(i.desc)))
		when (i.opcode) {
			Opcodes.GETSTATIC -> {
				stackPush(AstExprUtils.fastcast(AstExpr.STATIC_FIELD_ACCESS(ref), ref.type))
			}
			Opcodes.GETFIELD -> {
				val obj = AstExprUtils.fastcast(stackPop(), ref.containingTypeRef)
				stackPush(AstExprUtils.fastcast(AstExpr.INSTANCE_FIELD_ACCESS(ref, obj), ref.type))
			}
			Opcodes.PUTSTATIC -> {
				stmAdd(AstStm.SET_FIELD_STATIC(ref, AstExprUtils.fastcast(stackPop(), ref.type)))
			}
			Opcodes.PUTFIELD -> {
				val param = stackPop()
				val obj = AstExprUtils.fastcast(stackPop(), ref.containingTypeRef)
				stmAdd(AstStm.SET_FIELD_INSTANCE(ref, obj, AstExprUtils.fastcast(param, ref.type)))
			}
			else -> invalidOp
		}
	}

	//  peephole optimizations

	fun optimize(e: AstExpr.BINOP): AstExpr {
		return e
	}

	fun cast(expr: AstExpr, to: AstType) = AstExprUtils.cast(expr, to)
	fun fastcast(expr: AstExpr, to: AstType) = AstExprUtils.fastcast(expr, to)

	fun pushBinop(type: AstType, op: AstBinop) {
		val r = stackPop()
		val l = stackPop()
		stackPush(optimize(AstExprUtils.BINOP(type, l, op, r)))
	}

	fun arrayLoad(type: AstType): Unit {
		val index = stackPop()
		val array = stackPop()
		stackPush(AstExpr.ARRAY_ACCESS(fastcast(array, AstType.ARRAY(type)), fastcast(index, AstType.INT)))
	}

	fun arrayStore(elementType: AstType): Unit {
		val expr = stackPop()
		val index = stackPop()
		val array = stackPop()
		stmAdd(AstStm.SET_ARRAY(fastcast(array, AstType.ARRAY(elementType)), fastcast(index, AstType.INT), fastcast(expr, elementType)))
	}

	private var stackPopToLocalsItemsCount = 0

	fun stackPopToLocalsFixOrder() {
		if (stackPopToLocalsItemsCount == 0) return
		val last = stms.takeLast(stackPopToLocalsItemsCount)
		for (n in 0 until stackPopToLocalsItemsCount) stms.removeAt(stms.size - 1)
		stms.addAll(last.reversed())
		stackPopToLocalsItemsCount = 0
	}

	fun stackPopToLocalsCount(count: Int): List<AstExpr.LocalExpr> {
		val pairs = (0 until count).map {
			val v = stackPop()
			val local = locals.tempLocal(v.type)
			Pair(local, v)
		}

		//for (p in pairs.reversed()) {
		for (p in pairs) {
			if (stmSet(p.first, p.second)) {
				stackPopToLocalsItemsCount++
			}
		}

		return pairs.map { it.first }.reversed()
	}

	fun handleInsn(i: InsnNode): Unit {
		val op = i.opcode
		when (i.opcode) {
			Opcodes.NOP -> stmAdd(AstStm.NOP);
			Opcodes.ACONST_NULL -> stackPush(AstExpr.LITERAL(null))
			in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> stackPush(AstExpr.LITERAL((op - Opcodes.ICONST_0).toInt()))
			in Opcodes.LCONST_0..Opcodes.LCONST_1 -> stackPush(AstExpr.LITERAL((op - Opcodes.LCONST_0).toLong()))
			in Opcodes.FCONST_0..Opcodes.FCONST_2 -> stackPush(AstExpr.LITERAL((op - Opcodes.FCONST_0).toFloat()))
			in Opcodes.DCONST_0..Opcodes.DCONST_1 -> stackPush(AstExpr.LITERAL((op - Opcodes.DCONST_0).toDouble()))
			in Opcodes.IALOAD..Opcodes.SALOAD -> arrayLoad(PTYPES[op - Opcodes.IALOAD])
			in Opcodes.IASTORE..Opcodes.SASTORE -> arrayStore(PTYPES[op - Opcodes.IASTORE])
			Opcodes.POP -> {
				// We store it, so we don't lose all the calculated stuff!
				val pop = stackPopToLocalsCount(1)
				stackPopToLocalsFixOrder()
			}
			Opcodes.POP2 -> {
				val pop = if (stackPeek().type.isLongOrDouble()) stackPopToLocalsCount(1) else stackPopToLocalsCount(2)
				stackPopToLocalsFixOrder()
			}
			Opcodes.DUP -> {
				val value = stackPop()
				val local = locals.tempLocal(value.type)

				stmSet(local, value)
				stackPush(local)
				stackPush(local)
			}
		// value2, value1 → value1, value2, value1
		// insert a copy of the top value into the stack two values from the top. value1 and value2 must not be of the type double or long.
			Opcodes.DUP_X1 -> {
				//untestedWarn2("DUP_X1")
				val chunk1 = stackPopToLocalsCount(1)
				val chunk2 = stackPopToLocalsCount(1)
				stackPopToLocalsFixOrder()
				stackPushList(chunk1)
				stackPushList(chunk2)
				stackPushList(chunk1)
			}
		// value3, value2, value1 → value1, value3, value2, value1
		// insert a copy of the top value into the stack two (if value2 is double or long it takes up the entry of value3, too)
		// or three values (if value2 is neither double nor long) from the top
			Opcodes.DUP_X2 -> {
				val chunk1 = stackPopToLocalsCount(1)
				val chunk2 = if (stackPeek().type.isLongOrDouble()) stackPopToLocalsCount(1) else stackPopToLocalsCount(2)
				stackPopToLocalsFixOrder()
				stackPushList(chunk1)
				stackPushList(chunk2)
				stackPushList(chunk1)
			}
		// {value2, value1} → {value2, value1}, {value2, value1}
		// duplicate top two stack words (two values, if value1 is not double nor long; a single value, if value1 is double or long)
			Opcodes.DUP2 -> {
				val chunk1 = if (stackPeek().type.isLongOrDouble()) stackPopToLocalsCount(1) else stackPopToLocalsCount(2)
				stackPopToLocalsFixOrder()
				stackPushList(chunk1)
				stackPushList(chunk1)
			}
		// value3, {value2, value1} → {value2, value1}, value3, {value2, value1}
		// duplicate two words and insert beneath third word (see explanation above)
			Opcodes.DUP2_X1 -> {
				//untestedWarn2("DUP2_X1")
				val chunk1 = if (stackPeek().type.isLongOrDouble()) stackPopToLocalsCount(1) else stackPopToLocalsCount(2)
				val chunk2 = stackPopToLocalsCount(1)
				stackPopToLocalsFixOrder()
				stackPushList(chunk1)
				stackPushList(chunk2)
				stackPushList(chunk1)
			}
		// {value4, value3}, {value2, value1} → {value2, value1}, {value4, value3}, {value2, value1}
		// duplicate two words and insert beneath fourth word
			Opcodes.DUP2_X2 -> {
				//untestedWarn2("DUP2_X2")
				val chunk1 = if (stackPeek().type.isLongOrDouble()) stackPopToLocalsCount(1) else stackPopToLocalsCount(2)
				val chunk2 = if (stackPeek().type.isLongOrDouble()) stackPopToLocalsCount(1) else stackPopToLocalsCount(2)
				stackPopToLocalsFixOrder()
				stackPushList(chunk1)
				stackPushList(chunk2)
				stackPushList(chunk1)
			}
			Opcodes.SWAP -> {
				val v1 = stackPop()
				val v2 = stackPop()
				stackPopToLocalsFixOrder()
				stackPush(v1)
				stackPush(v2)
			}
			in Opcodes.INEG..Opcodes.DNEG -> stackPush(AstExpr.UNOP(AstUnop.NEG, stackPop()))

			in Opcodes.IADD..Opcodes.DADD -> pushBinop(PTYPES[op - Opcodes.IADD], AstBinop.ADD)
			in Opcodes.ISUB..Opcodes.DSUB -> pushBinop(PTYPES[op - Opcodes.ISUB], AstBinop.SUB)
			in Opcodes.IMUL..Opcodes.DMUL -> pushBinop(PTYPES[op - Opcodes.IMUL], AstBinop.MUL)
			in Opcodes.IDIV..Opcodes.DDIV -> pushBinop(PTYPES[op - Opcodes.IDIV], AstBinop.DIV)
			in Opcodes.IREM..Opcodes.DREM -> pushBinop(PTYPES[op - Opcodes.IREM], AstBinop.REM)
			in Opcodes.ISHL..Opcodes.LSHL -> pushBinop(PTYPES[op - Opcodes.ISHL], AstBinop.SHL)
			in Opcodes.ISHR..Opcodes.LSHR -> pushBinop(PTYPES[op - Opcodes.ISHR], AstBinop.SHR)
			in Opcodes.IUSHR..Opcodes.LUSHR -> pushBinop(PTYPES[op - Opcodes.IUSHR], AstBinop.USHR)
			in Opcodes.IAND..Opcodes.LAND -> pushBinop(PTYPES[op - Opcodes.IAND], AstBinop.AND)
			in Opcodes.IOR..Opcodes.LOR -> pushBinop(PTYPES[op - Opcodes.IOR], AstBinop.OR)
			in Opcodes.IXOR..Opcodes.LXOR -> pushBinop(PTYPES[op - Opcodes.IXOR], AstBinop.XOR)

			Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> stackPush(fastcast(stackPop(), AstType.LONG))
			Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> stackPush(fastcast(stackPop(), AstType.FLOAT))
			Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> stackPush(fastcast(stackPop(), AstType.DOUBLE))
			Opcodes.L2I, Opcodes.F2I, Opcodes.D2I -> stackPush(fastcast(stackPop(), AstType.INT))
			Opcodes.I2B -> stackPush(fastcast(stackPop(), AstType.BYTE))
			Opcodes.I2C -> stackPush(fastcast(stackPop(), AstType.CHAR))
			Opcodes.I2S -> stackPush(fastcast(stackPop(), AstType.SHORT))

			Opcodes.LCMP -> pushBinop(AstType.LONG, AstBinop.LCMP)
			Opcodes.FCMPL -> pushBinop(AstType.FLOAT, AstBinop.CMPL)
			Opcodes.FCMPG -> pushBinop(AstType.FLOAT, AstBinop.CMPG)
			Opcodes.DCMPL -> pushBinop(AstType.DOUBLE, AstBinop.CMPL)
			Opcodes.DCMPG -> pushBinop(AstType.DOUBLE, AstBinop.CMPG)
			in Opcodes.IRETURN..Opcodes.ARETURN -> {
				val ret = stackPop()
				dumpExprs()
				stmAdd(AstStm.RETURN(fastcast(ret, this.methodType.ret)))
			}
			Opcodes.RETURN -> {
				dumpExprs()
				stmAdd(AstStm.RETURN(null))
			}
			Opcodes.ARRAYLENGTH -> stackPush(AstExpr.ARRAY_LENGTH(stackPop()))
			Opcodes.ATHROW -> {
				val ret = stackPop()
				dumpExprs()
				stmAdd(AstStm.THROW(ret))
			}
			Opcodes.MONITORENTER -> stmAdd(AstStm.MONITOR_ENTER(stackPop()))
			Opcodes.MONITOREXIT -> stmAdd(AstStm.MONITOR_EXIT(stackPop()))
			else -> invalidOp("$op")
		}
	}

	fun handleMultiArray(i: MultiANewArrayInsnNode) {
		when (i.opcode) {
			Opcodes.MULTIANEWARRAY -> {
				stackPush(AstExpr.NEW_ARRAY(AstType.REF_INT(i.desc) as AstType.ARRAY, (0 until i.dims).map { stackPop() }.reversed()))
			}
			else -> invalidOp("$i")
		}
	}

	fun handleType(i: TypeInsnNode) {
		val type = AstType.REF_INT(i.desc)
		when (i.opcode) {
			Opcodes.NEW -> stackPush(fastcast(AstExpr.NEW(type as AstType.REF), AstType.OBJECT))
			Opcodes.ANEWARRAY -> stackPush(AstExpr.NEW_ARRAY(AstType.ARRAY(type), listOf(stackPop())))
			Opcodes.CHECKCAST -> stackPush(cast(stackPop(), type))
			Opcodes.INSTANCEOF -> stackPush(AstExpr.INSTANCE_OF(stackPop(), type))
			else -> invalidOp("$i")
		}
	}

	fun handleVar(i: VarInsnNode) {
		val op = i.opcode
		val index = i.`var`

		fun load(type: AstType) {
			stackPush(locals.local(type, index))
		}

		fun store(type: AstType) {
			val expr = stackPop()
			val newLocal = locals.local(type, index)
			stmSet(newLocal, expr)
		}

		when (op) {
			in Opcodes.ILOAD..Opcodes.ALOAD -> load(PTYPES[op - Opcodes.ILOAD])
			in Opcodes.ISTORE..Opcodes.ASTORE -> store(PTYPES[op - Opcodes.ISTORE])
			Opcodes.RET -> deprecated
			else -> invalidOp
		}
	}

	fun handleJump(i: JumpInsnNode) {
		val op = i.opcode

		fun addJump(cond: AstExpr?, label: AstLabel) {
			//val stack = preserveStack()

			restoreStack(preserveStack())
			labels.ref(label)
			stms.add(AstStm.IF_GOTO(label, cond))
			//restoreStack(stack)
		}

		fun addJump0(op: AstBinop) {
			addJump(AstExprUtils.BINOP(AstType.BOOL, stackPop(), op, AstExpr.LITERAL(0)), labels.label(i.label))
		}

		fun addJumpNull(op: AstBinop) {
			addJump(AstExprUtils.BINOP(AstType.BOOL, stackPop(), op, AstExpr.LITERAL(null)), labels.label(i.label))
		}

		fun addJump2(op: AstBinop) {
			val r = stackPop()
			val l = stackPop()
			addJump(AstExprUtils.BINOP(AstType.BOOL, l, op, r), labels.label(i.label))
		}

		when (op) {
			in Opcodes.IFEQ..Opcodes.IFLE -> addJump0(CTYPES[op - Opcodes.IFEQ]);
			in Opcodes.IFNULL..Opcodes.IFNONNULL -> addJumpNull(CTYPES[op - Opcodes.IFNULL])
			in Opcodes.IF_ICMPEQ..Opcodes.IF_ACMPNE -> addJump2(CTYPES[op - Opcodes.IF_ICMPEQ])
			Opcodes.GOTO -> addJump(null, labels.label(i.label))
			Opcodes.JSR -> deprecated
			else -> invalidOp
		}
	}

	fun handleLdc(i: LdcInsnNode) {
		// {@link Integer}, a {@link Float}, a {@link Long}, a {@link Double}, a
		// {@link String} or a {@link org.objectweb.asm.Type}.
		val cst = i.cst
		if (cst is Type) {
			stackPush(AstExpr.CLASS_CONSTANT(AstType.REF_INT(cst.internalName)))
		} else {
			stackPush(AstExpr.LITERAL(cst))
		}
	}

	fun handleInt(i: IntInsnNode) {
		when (i.opcode) {
			Opcodes.BIPUSH -> stackPush(AstExpr.LITERAL(i.operand.toByte()))
			Opcodes.SIPUSH -> stackPush(AstExpr.LITERAL(i.operand.toShort()))
			Opcodes.NEWARRAY -> {
				val type = when (i.operand) {
					Opcodes.T_BOOLEAN -> AstType.BOOL
					Opcodes.T_CHAR -> AstType.CHAR
					Opcodes.T_FLOAT -> AstType.FLOAT
					Opcodes.T_DOUBLE -> AstType.DOUBLE
					Opcodes.T_BYTE -> AstType.BYTE
					Opcodes.T_SHORT -> AstType.SHORT
					Opcodes.T_INT -> AstType.INT
					Opcodes.T_LONG -> AstType.LONG
					else -> invalidOp
				}
				stackPush(AstExpr.NEW_ARRAY(AstType.ARRAY(type, 1), listOf(stackPop())))
			}
			else -> invalidOp
		}
	}

	fun handleMethod(i: MethodInsnNode) {
		val type = AstType.REF_INT(i.owner)
		val clazz = if (type is AstType.REF) type else AstType.OBJECT
		val methodRef = fix(com.jtransc.ast.AstMethodRef(clazz.fqname.fqname, i.name, AstType.demangleMethod(i.desc)))
		val isSpecial = i.opcode == Opcodes.INVOKESPECIAL

		val args = methodRef.type.args.reversed().map { fastcast(stackPop(), it.type) }.reversed()
		val obj = if (i.opcode != Opcodes.INVOKESTATIC) stackPop() else null

		when (i.opcode) {
			Opcodes.INVOKESTATIC -> {
				stackPush(AstExpr.CALL_STATIC(clazz, methodRef, args, isSpecial))
			}
			Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL -> {
				if (obj!!.type !is AstType.REF) {
					//invalidOp("Obj must be an object $obj, but was ${obj.type}")
				}
				val obj = fastcast(obj, methodRef.containingClassType)
				if (i.opcode != Opcodes.INVOKESPECIAL) {
					stackPush(AstExpr.CALL_INSTANCE(obj, methodRef, args, isSpecial))
				} else {
					stackPush(AstExpr.CALL_SPECIAL(AstExprUtils.fastcast(obj, methodRef.containingClassType), methodRef, args, isSpecial = true))
				}
			}
			else -> invalidOp
		}

		if (methodRef.type.retVoid) {
			//preserveStack()
			stmAdd(AstStm.STM_EXPR(stackPop()))
		}
	}

	fun handleLookupSwitch(i: LookupSwitchInsnNode) {
		stmAdd(AstStm.SWITCH_GOTO(
			stackPop(),
			labels.ref(labels.label(i.dflt)),
			i.keys.cast<Int>().zip(i.labels.cast<LabelNode>().map { labels.ref(labels.label(it)) })
		))
	}

	fun handleTableSwitch(i: TableSwitchInsnNode) {
		stmAdd(AstStm.SWITCH_GOTO(
			stackPop(),
			labels.ref(labels.label(i.dflt)),
			(i.min..i.max).zip(i.labels.cast<LabelNode>().map { labels.ref(labels.label(it)) })
		))
	}

	fun handleInvokeDynamic(i: InvokeDynamicInsnNode) {
		stackPush(AstExprUtils.INVOKE_DYNAMIC(
			AstMethodWithoutClassRef(i.name, AstType.demangleMethod(i.desc)),
			i.bsm.ast,
			i.bsmArgs.map {
				when (it) {
					is org.objectweb.asm.Type -> when (it.sort) {
						Type.METHOD -> AstExpr.METHODTYPE_CONSTANT(AstType.demangleMethod(it.descriptor))
						else -> noImpl("${it.sort} : ${it}")
					}
					is org.objectweb.asm.Handle -> {
						val kind = AstMethodHandle.Kind.fromId(it.tag)
						val type = AstType.demangleMethod(it.desc)
						AstExpr.METHODHANDLE_CONSTANT(AstMethodHandle(type, AstMethodRef(FqName.fromInternal(it.owner), it.name, type), kind))
					}
					else -> AstExpr.LITERAL(it)
				}
			}
		))
	}

	fun handleLabel(i: LabelNode) {
		lastLabel = i
		//dumpExprs()
		stmAdd(AstStm.STM_LABEL(labels.label(i)))
	}

	fun handleIinc(i: IincInsnNode) {
		val local = locals.local(AstType.INT, i.`var`)
		stmSet(local, local + AstExpr.LITERAL(i.incr))
	}

	fun handleLineNumber(i: LineNumberNode) {
		lastLine = i.line
		stmAdd(AstStm.LINE(i.line))
	}

	fun preserveStackLocal(index: Int, type: AstType): AstExpr.LocalExpr {
		return locals.local(type, index, "s")
	}

	fun dumpExprs() {
		while (stack.isNotEmpty()) {
			stmAdd(AstStm.STM_EXPR(stackPop()))
		}
	}

	fun preserveStack(): List<AstExpr.LocalExpr> {
		if (stack.isEmpty()) {
			return Collections.EMPTY_LIST as List<AstExpr.LocalExpr>
		} else {
			val items = arrayListOf<AstExpr.LocalExpr>()
			val preservedStack = (0 until stack.size).map { stackPop() }

			if (DEBUG) println("--")
			for ((index2, value) in preservedStack.withIndex()) {
				//val index = index2
				val index = preservedStack.size - index2 - 1
				val local = preserveStackLocal(index, value.type)
				if (DEBUG) println("PRESERVE: $local : $index, ${value.type}")
				stmSet(local, value)
				items.add(local)
			}
			return items
		}
	}

	fun restoreStack(stackToRestore: List<AstExpr.LocalExpr>) {
		if (stackToRestore.size >= 2) {
			//println("stackToRestore.size:" + stackToRestore.size)
		}
		for (i in stackToRestore.reversed()) {
			if (DEBUG) println("RESTORE: $i")
			// @TODO: avoid reversed by inserting in the right order!
			this.stack.push(i)
		}
	}

	fun handleFrame(i: FrameNode) {
		stack.clear()
		// validated order

		if (lastLabel in labels.referencedHandlers) {
			if (i.stack.size != 1) invalidOp("catch handler should have just one stack element!?")

			stackPush(AstExpr.CAUGHT_EXCEPTION())
		} else {

			for ((index2, typeValue) in i.stack.withIndex()) {
				val index = index2
				//val index = i.stack.size - index2 - 1
				//val type = LiteralToAstType(typeValue)

				val type = when (typeValue) {
					Opcodes.TOP -> invalidOp
					Opcodes.INTEGER -> AstType.INT
					Opcodes.FLOAT -> AstType.FLOAT
					Opcodes.DOUBLE -> AstType.DOUBLE
					Opcodes.LONG -> AstType.LONG
					Opcodes.NULL -> AstType.OBJECT
					Opcodes.UNINITIALIZED_THIS -> AstType.OBJECT
					is String -> AstType.OBJECT
					is LabelNode -> AstType.OBJECT
				//else -> LiteralToAstType(typeValue)
					else -> invalidOp("Invalid: $typeValue")
				}


				stackPush(preserveStackLocal(index, type))
				if (DEBUG) println("$index: push($typeValue : ${typeValue?.javaClass})")
			}
		}
	}

	fun call(entry: AbstractInsnNode, input: BasicBlock.Input): BasicBlock {
		var i: AbstractInsnNode? = entry

		if (DEBUG) {
			println("--------------------------------------------------------------------")
			println("::::::::::::: ${clazz.name}.${method.name}:${method.desc}")
			println("--------------------------------------------------------------------")
		}

		while (i != null) {
			if (DEBUG) println(AsmOpcode.disasm(i))
			when (i) {
				is FieldInsnNode -> handleField(i)
				is InsnNode -> handleInsn(i)
				is TypeInsnNode -> handleType(i)
				is VarInsnNode -> handleVar(i)
				is JumpInsnNode -> handleJump(i)
				is LdcInsnNode -> handleLdc(i)
				is IntInsnNode -> handleInt(i)
				is MethodInsnNode -> handleMethod(i)
				is LookupSwitchInsnNode -> handleLookupSwitch(i)
				is TableSwitchInsnNode -> handleTableSwitch(i)
				is InvokeDynamicInsnNode -> handleInvokeDynamic(i)
				is LabelNode -> handleLabel(i)
				is IincInsnNode -> handleIinc(i)
				is LineNumberNode -> handleLineNumber(i)
				is FrameNode -> handleFrame(i)
				is MultiANewArrayInsnNode -> handleMultiArray(i)
				else -> invalidOp("$i")
			}
			i = i.next
		}

		dumpExprs()

		return BasicBlock(input, entry, stms)
	}
}
