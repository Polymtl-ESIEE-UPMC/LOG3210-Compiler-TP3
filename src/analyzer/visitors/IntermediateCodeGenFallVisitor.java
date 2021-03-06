package analyzer.visitors;

import analyzer.ast.*;
import com.sun.org.apache.xpath.internal.operations.Bool;
import org.omg.PortableInterceptor.SYSTEM_EXCEPTION;
import sun.awt.Symbol;

import java.awt.*;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Vector;


/**
 * Created: 19-02-15
 * Last Changed: 19-10-20
 * Author: Félix Brunet & Doriane Olewicki
 *
 * Description: Ce visiteur explore l'AST et génère un code intermédiaire.
 */

public class IntermediateCodeGenFallVisitor implements ParserVisitor {

    //le m_writer est un Output_Stream connecter au fichier "result". c'est donc ce qui permet de print dans les fichiers
    //le code généré.
    private final PrintWriter m_writer;

    public IntermediateCodeGenFallVisitor(PrintWriter writer) {
        m_writer = writer;
    }
    public HashMap<String, VarType> SymbolTable = new HashMap<>();

    private int id = 0;
    private int label = 0;
    /*
    génère une nouvelle variable temporaire qu'il est possible de print
    À noté qu'il serait possible de rentrer en conflit avec un nom de variable définit dans le programme.
    Par simplicité, dans ce tp, nous ne concidérerons pas cette possibilité, mais il faudrait un générateur de nom de
    variable beaucoup plus robuste dans un vrai compilateur.
     */
    private String genId() {
        return "_t" + id++;
    }

    //génère un nouveau Label qu'il est possible de print.
    private String genLabel() {
        return "_L" + label++;
    }

    @Override
    public Object visit(SimpleNode node, Object data) {
        return data;
    }

    @Override
    public Object visit(ASTProgram node, Object data)  {
        node.childrenAccept(this, data);
        return null;
    }

    /*
    Code fournis pour remplir la table de symbole.
    Les déclarations ne sont plus utile dans le code à trois adresse.
    elle ne sont donc pas concervé.
     */
    @Override
    public Object visit(ASTDeclaration node, Object data) {
        ASTIdentifier id = (ASTIdentifier) node.jjtGetChild(0);
        VarType t;
        if(node.getValue().equals("bool")) {
            t = VarType.Bool;
        } else {
            t = VarType.Number;
        }
        SymbolTable.put(id.getValue(), t);
        return null;
    }

    @Override
    public Object visit(ASTBlock node, Object data) {
        node.childrenAccept(this, data);
        return null;
    }

    @Override
    public Object visit(ASTStmt node, Object data) {
        node.childrenAccept(this, data);
        return null;
    }

    /*
    le If Stmt doit vérifier s'il à trois enfants pour savoir s'il s'agit d'un "if-then" ou d'un "if-then-else".
     */
    @Override
    public Object visit(ASTIfStmt node, Object data) {
        if (node.jjtGetNumChildren() < 3) {
            String B_true = "fall";
            String S_next = genLabel();
            BoolLabel B_label = new BoolLabel(B_true, S_next);
            String S1_next = S_next;
            node.jjtGetChild(0).jjtAccept(this, B_label);
            B_label.next = S1_next;
            node.jjtGetChild(1).jjtAccept(this, B_label);
            m_writer.println(S_next);
        } else {
            BoolLabel B_label = new BoolLabel("fall", genLabel());
            String S_next = genLabel();
            String S1_next = S_next;
            String S2_next = S_next;
            node.jjtGetChild(0).jjtAccept(this, B_label);
            B_label.next = S1_next;
            node.jjtGetChild(1).jjtAccept(this, B_label);
            m_writer.println("goto " + S_next);
            m_writer.println(B_label.lFalse);
            B_label.next = S2_next;
            node.jjtGetChild(2).jjtAccept(this, B_label);
            m_writer.println(S_next);
        }
        return null;
    }

    @Override
    public Object visit(ASTWhileStmt node, Object data) {
        String begin = genLabel();
        String B_true = "fall";
        String S_next = genLabel();
        BoolLabel B_label = new BoolLabel(B_true, S_next);
        String S1_next = begin;
        B_label.next = S1_next;
        m_writer.println(begin);
        node.jjtGetChild(0).jjtAccept(this, B_label);
        node.jjtGetChild(1).jjtAccept(this, B_label);
        m_writer.println("goto " + begin);
        m_writer.println(S_next);
        return null;
    }

    /*
     *  la difficulté est d'implémenter le "short-circuit" des opérations logiques combinez à l'enregistrement des
     *  valeurs booléennes dans des variables.
     *
     *  par exemple,
     *  a = b || c && !d
     *  deviens
     *  if(b)
     *      t1 = 1
     *  else if(c)
     *      if(d)
     *         t1 = 1
     *      else
     *         t1 = 0
     *  else
     *      t1 = 0
     *  a = t1
     *
     *  qui est équivalent à :
     *
     *  if b goto LTrue
     *  ifFalse c goto LFalse
     *  ifFalse d goto LTrue
     *  goto LFalse
     *  //Assign
     *  LTrue
     *  a = 1
     *  goto LEnd
     *  LFalse
     *  a = 0
     *  LEnd
     *  //End Assign
     *
     *  mais
     *
     *  a = 1 * 2 + 3
     *
     *  deviens
     *
     *  //expr
     *  t1 = 1 * 2
     *  t2 = t1 + 3
     *  //expr
     *  a = t2
     *
     *  et
     *
     *  if(b || c && !d)
     *
     *  deviens
     *
     *  //expr
     *  if b goto LTrue
     *  ifFalse c goto LFalse
     *  ifFalse d goto LTrue
     *  goto LFalse
     *  //expr
     *  //if
     *  LTrue
     *  codeS1
     *  goto lEnd
     *  LFalse
     *  codeS2
     *  LEnd
     *  //end if
     *
     *
     *  Il faut donc dès le départ vérifier dans la table de symbole le type de la variable à gauche, et généré du
     *  code différent selon ce type.
     *
     *  Pour avoir l'id de la variable de gauche de l'assignation, il peut être plus simple d'aller chercher la valeur
     *  du premier enfant sans l'accepter.
     *  De la sorte, on accept un noeud "Identifier" seulement lorsqu'on l'utilise comme référence (à droite d'une assignation)
     *  Cela simplifie le code de part et d'autre.
     *
     *  Aussi, il peut être pertinent d'extraire le code de l'assignation dans une fonction privée, parce que ce code
     *  sera utile pour les noeuds de comparaison (plus d'explication au commentaire du noeud en question.)
     *  la signature de la fonction que j'ai utilisé pour se faire est :
     *  private String generateAssignCode(Node node, String tId);
     *  ou "node" est le noeud de l'expression représentant la valeur, et tId est le nom de la variable ou assigné
     *  la valeur.
     *
     *  Il est normal (et probablement inévitable concidérant la structure de l'arbre)
     *  de généré inutilement des labels (ou des variables temporaire) qui ne sont pas utilisé ni imprimé dans le code résultant.
     */
    @Override
    public Object visit(ASTAssignStmt node, Object data) {
        String identifier = ((ASTIdentifier) node.jjtGetChild(0)).getValue();
        if(SymbolTable.get(identifier).equals(VarType.Number)) {
            m_writer.println(identifier + " = " + node.jjtGetChild(1).jjtAccept(this, data));
        } else {
            assignBooleanCodeGen(node.jjtGetChild(1), identifier, data);
        }
        return null;
    }

    private String assignBooleanCodeGen(Node B, String identifier, Object data) {
        BoolLabel B_label = new BoolLabel("fall", genLabel());
        String S_herite_next = null;
        if (data != null) S_herite_next= ((BoolLabel) data).next;
        String S_next;
        if (S_herite_next == null)  S_next = genLabel();
        else S_next = S_herite_next;
        B.jjtAccept(this, new BoolLabel(B_label));
        m_writer.println(identifier + " = 1");
        m_writer.println("goto " + S_next);
        m_writer.println(B_label.lFalse);
        m_writer.println(identifier + " = 0");
        m_writer.println(S_next);
        return identifier;
    }


    //Il n'y a probablement rien à faire ici
    @Override
    public Object visit(ASTExpr node, Object data){
        return node.jjtGetChild(0).jjtAccept(this, data);
    }

    //Expression arithmétique
    /*
    Les expressions arithmétique add et mult fonctionne exactement de la même manière. c'est pourquoi
    il est plus simple de remplir cette fonction une fois pour avoir le résultat pour les deux noeuds.

    On peut bouclé sur "ops" ou sur node.jjtGetNumChildren(),
    la taille de ops sera toujours 1 de moins que la taille de jjtGetNumChildren
     */
    public String exprCodeGen(SimpleNode node, Object data, Vector<String> ops) {
        String E1_address = (String) node.jjtGetChild(0).jjtAccept(this, data);
        String E_address;
        for (int i = 0; i < ops.size(); i++) {
            String E2_address = (String) node.jjtGetChild(i+1).jjtAccept(this, data);
            E_address = genId();
            m_writer.println(E_address + " = " + E1_address + " " + ops.get(i) + " " + E2_address);
            E1_address = E_address;
        }
        return E1_address;
    }

    @Override
    public Object visit(ASTAddExpr node, Object data) {
        return this.exprCodeGen(node, data, node.getOps());
    }

    @Override
    public Object visit(ASTMulExpr node, Object data) {
        return this.exprCodeGen(node, data, node.getOps());
    }

    //UnaExpr est presque pareil au deux précédente. la plus grosse différence est qu'il ne va pas
    //chercher un deuxième noeud enfant pour avoir une valeur puisqu'il s'agit d'une opération unaire.
    @Override
    public Object visit(ASTUnaExpr node, Object data) {
        String E1_address = (String) node.jjtGetChild(0).jjtAccept(this, data);
        String E_address;
        for (int i = 0; i < node.getOps().size(); i++) {
            E_address = genId();
            m_writer.println(E_address + " = " + node.getOps().get(i) + " " + E1_address);
            E1_address = E_address;
        }
        return E1_address;
    }

    //expression logique

    /*

    Rappel, dans le langague, le OU et le ET on la même priorité, et sont associatif à droite par défaut.
    ainsi :
    "a = a || || a2 || b && c || d" est interprété comme "a = a || a2 || (b && (c || d))"
     */
    @Override
    public Object visit(ASTBoolExpr node, Object data) {
        BoolLabel B_label = (BoolLabel) data;
        if (node.getOps().size() == 0) return node.jjtGetChild(0).jjtAccept(this, new BoolLabel(B_label));
        for (int i = node.getOps().size(); i > 0; i--) {
            if (node.getOps().get(i-1).equals("||")) {
                BoolLabel B1_label;
                if (B_label.lTrue.equals("fall")) B1_label = new BoolLabel(genLabel(), "fall");
                else B1_label = new BoolLabel(B_label.lTrue, "fall");
                BoolLabel B2_label = new BoolLabel(B_label);
                if (B_label.lTrue.equals("fall")) {
                    node.jjtGetChild(i-1).jjtAccept(this, new BoolLabel(B1_label));
                    node.jjtGetChild(i).jjtAccept(this, new BoolLabel(B2_label));
                    m_writer.println(B1_label.lTrue);
                } else {
                    node.jjtGetChild(i-1).jjtAccept(this, new BoolLabel(B1_label));
                    node.jjtGetChild(i).jjtAccept(this, new BoolLabel(B2_label));
                }
            } else {
                BoolLabel B1_label;
                if (B_label.lFalse.equals("fall")) B1_label = new BoolLabel("fall", genLabel());
                else B1_label = new BoolLabel("fall", B_label.lFalse);
                BoolLabel B2_label = new BoolLabel(B_label);
                if (B_label.lFalse.equals("fall")) {
                    node.jjtGetChild(i-1).jjtAccept(this, new BoolLabel(B1_label));
                    node.jjtGetChild(i).jjtAccept(this, new BoolLabel(B2_label));
                    m_writer.println(B1_label.lFalse);
                } else {
                    node.jjtGetChild(i-1).jjtAccept(this, new BoolLabel(B1_label));
                    node.jjtGetChild(i).jjtAccept(this, new BoolLabel(B2_label));
                }
            }
        }
        return null;
    }


    //cette fonction privé est utile parce que le code pour généré le goto pour les opérateurs de comparaison est le même
    //que celui pour le référencement de variable booléenne.
    //le code est très simple avant l'optimisation, mais deviens un peu plus long avec l'optimisation.
    private void genCodeRelTestJump(String labelTrue, String labelfalse, String strSegment) {
        if (labelTrue != null && labelfalse != null) {
            m_writer.println("if " + strSegment + " goto " + labelTrue);
            m_writer.println("goto " + labelfalse);
        } else if (labelTrue != null) {
            m_writer.println("if" + strSegment + " goto " + labelTrue);
        } else if (labelfalse != null) {
            m_writer.println("if" + strSegment + " goto " + labelfalse);
        }
    }


    //une partie de la fonction à été faite pour donner des pistes, mais comme tous le reste du fichier, tous est libre
    //à modification.
    /*
    À ajouté : la comparaison est plus complexe quand il s'agit d'une comparaison de booléen.
    Le truc est de :
    1. vérifier qu'il s'agit d'une comparaison de nombre ou de booléen.
        On peut Ce simplifier la vie et le déterminer simplement en regardant si les enfants retourne une valeur ou non, à condition
        de s'être assurer que les valeurs booléennes retourne toujours null.
     2. s'il s'agit d'une comparaison de nombre, on peut faire le code simple par "genCodeRelTestJump(B, test)"
     3. s'il s'agit d'une comparaison de booléen, il faut enregistrer la valeur gauche et droite de la comparaison dans une variable temporaire,
        en utilisant le même code que pour l'assignation, deux fois. (mettre ce code dans une fonction deviens alors pratique)
        avant de faire la comparaison "genCodeRelTestJump(B, test)" avec les deux variables temporaire.

        notez que cette méthodes peut sembler peu efficace pour certain cas, mais qu'avec des passes d'optimisations subséquente, (que l'on
        ne fera pas dans le cadre du TP), on pourrait s'assurer que le code produit est aussi efficace qu'il peut l'être.
     */
    @Override
    public Object visit(ASTCompExpr node, Object data) {
        if (node.getValue() == null) return node.jjtGetChild(0).jjtAccept(this, data);
        BoolLabel B_label = (BoolLabel) data;

        Boolean number_comparison;
        Node child = node.jjtGetChild(0);
        while (!(child instanceof ASTGenValue)) {
            child = child.jjtGetChild(0);
        }
        child = child.jjtGetChild(0);
        if (child instanceof ASTIdentifier) {
            if(SymbolTable.get(((ASTIdentifier)child).getValue()).equals(VarType.Number)) number_comparison = true;
            else number_comparison = false;
        } else if (child instanceof ASTIntValue) {
            number_comparison = true;
        } else {
            number_comparison = false;
        }

        if (number_comparison) {
            if(!B_label.lTrue.equals("fall") && !B_label.lFalse.equals("fall")) {
                String E1_address = (String) node.jjtGetChild(0).jjtAccept(this, data);
                String E2_address = (String) node.jjtGetChild(1).jjtAccept(this, data);
                genCodeRelTestJump(B_label.lTrue, B_label.lFalse, E1_address + " " + node.getValue() + " " + E2_address);
            } else {
                if(!B_label.lTrue.equals("fall")) {
                    String E1_address = (String) node.jjtGetChild(0).jjtAccept(this, data);
                    String E2_address = (String) node.jjtGetChild(1).jjtAccept(this, data);
                    genCodeRelTestJump(B_label.lTrue, null, " " + E1_address + " " + node.getValue() + " " + E2_address);
                } else {
                    if(!B_label.lFalse.equals("fall")) {
                        String E1_address = (String) node.jjtGetChild(0).jjtAccept(this, data);
                        String E2_address = (String) node.jjtGetChild(1).jjtAccept(this, data);
                        genCodeRelTestJump(null, B_label.lFalse, "False " + E1_address + " " + node.getValue() + " " + E2_address);
                    } else {
                        m_writer.println("error");
                    }
                }
            }
        } else {
            if(!B_label.lTrue.equals("fall") && !B_label.lFalse.equals("fall")) {
                String E1_address = assignBooleanCodeGen(node.jjtGetChild(0), genId(), data);
                String E2_address = assignBooleanCodeGen(node.jjtGetChild(1), genId(), data);
                genCodeRelTestJump(B_label.lTrue, B_label.lFalse, E1_address + " " + node.getValue() + " " + E2_address);
            } else {
                if(!B_label.lTrue.equals("fall")) {
                    String E1_address = assignBooleanCodeGen(node.jjtGetChild(0), genId(), data);
                    String E2_address = assignBooleanCodeGen(node.jjtGetChild(1), genId(), data);
                    genCodeRelTestJump(B_label.lTrue, null, " " + E1_address + " " + node.getValue() + " " + E2_address);
                } else {
                    if(!B_label.lFalse.equals("fall")) {
                        String E1_address = assignBooleanCodeGen(node.jjtGetChild(0), genId(), data);
                        String E2_address = assignBooleanCodeGen(node.jjtGetChild(1), genId(), data);
                        genCodeRelTestJump(null, B_label.lFalse, "False " + E1_address + " " + node.getValue() + " " + E2_address);
                    } else {
                        m_writer.println("error");
                    }
                }
            }
        }
        return null;
    }


    /*
    Même si on peut y avoir un grand nombre d'opération, celle-ci s'annullent entre elle.
    il est donc intéressant de vérifier si le nombre d'opération est pair ou impaire.
    Si le nombre d'opération est pair, on peut simplement ignorer ce noeud.
     */
    @Override
    public Object visit(ASTNotExpr node, Object data) {
        BoolLabel B_label = (BoolLabel) data;
        if (node.getOps().size() == 0) return node.jjtGetChild(0).jjtAccept(this, B_label);
        for (int i = 0; i < node.getOps().size(); i++) {
            BoolLabel B1_label = new BoolLabel(B_label.lFalse, B_label.lTrue);
            node.jjtGetChild(0).jjtAccept(this, B1_label);
            B_label = new BoolLabel(B1_label);
        }
        return null;
    }

    @Override
    public Object visit(ASTGenValue node, Object data) {
        return node.jjtGetChild(0).jjtAccept(this, data);
    }

    /*
    BoolValue ne peut pas simplement retourné sa valeur à son parent contrairement à GenValue et IntValue,
    Il doit plutôt généré des Goto direct, selon sa valeur.
     */
    @Override
    public Object visit(ASTBoolValue node, Object data) {
        if (node.getValue()) {
            if (!((BoolLabel) data).lTrue.equals("fall")) m_writer.println("goto " + ((BoolLabel) data).lTrue);
        }
        else {
            if (!((BoolLabel) data).lFalse.equals("fall")) m_writer.println("goto " + ((BoolLabel) data).lFalse);
        }
        return null;
    }


    /*
    si le type de la variable est booléenne, il faudra généré des goto ici.
    le truc est de faire un "if value == 1 goto Label".
    en effet, la structure "if valeurBool goto Label" n'existe pas dans la syntaxe du code à trois adresse.
     */
    @Override
    public Object visit(ASTIdentifier node, Object data) {
        if(SymbolTable.get(node.getValue()).equals(VarType.Number)) return node.getValue();
        BoolLabel B_label = (BoolLabel) data;

        if (!B_label.lTrue.equals("fall") && !B_label.lFalse.equals("fall")) {
            genCodeRelTestJump(B_label.lTrue, B_label.lFalse, node.getValue() + " == 1");
        } else {
            if (!B_label.lTrue.equals("fall")) genCodeRelTestJump(B_label.lTrue, null, " " + node.getValue() + " == 1");
            else if (!B_label.lFalse.equals("fall")) {
                genCodeRelTestJump(null, B_label.lFalse, "False " + node.getValue() + " == 1");
            } else {
                m_writer.println("error");
            }
        }
//        genCodeRelTestJump(B_label.lTrue, B_label.lFalse, node.getValue() + " == 1");
        return null;
    }

    @Override
    public Object visit(ASTIntValue node, Object data) {
        return ((Integer)node.getValue()).toString();
    }


    @Override
    public Object visit(ASTSwitchStmt node, Object data) {
        String temp = (String) node.jjtGetChild(0).jjtAccept(this, data);
        String test = genLabel();
        String next = genLabel();
        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<String> address = new ArrayList<String>();
        m_writer.println("goto " + test);
        for (int i = 1; i < node.jjtGetNumChildren(); i++) {
            String label = genLabel();
            labels.add(label);
            m_writer.println(label);
            address.add((String) node.jjtGetChild(i).jjtAccept(this, data));
            m_writer.println("goto " + next);
        }
        m_writer.println(test);
        for (int counter = 0; counter < labels.size(); counter++) {
            if (address.get(counter) == null) m_writer.println("goto " + labels.get(counter));
            else genCodeRelTestJump(labels.get(counter), null, " " + temp + " == " + address.get(counter));
        }
        m_writer.println(next);
        return null;
    }

    @Override
    public Object visit(ASTCaseStmt node, Object data) {
        node.jjtGetChild(1).jjtAccept(this, data);
        return node.jjtGetChild(0).jjtAccept(this, data);
    }

    @Override
    public Object visit(ASTDefaultStmt node, Object data) {
        node.childrenAccept(this, data);
        return null;
    }

    //des outils pour vous simplifier la vie et vous enligner dans le travail
    public enum VarType {
        Bool,
        Number
    }

    //utile surtout pour envoyé de l'informations au enfant des expressions logiques.
    private class BoolLabel {
        public String lTrue = null;
        public String lFalse = null;
        public String next = null;

        public BoolLabel(String ltrue, String lfalse) {
            lTrue = ltrue;
            lFalse = lfalse;
        }

        public BoolLabel (BoolLabel b_label) {
            if (b_label != null) {
                lTrue = b_label.lTrue;
                lFalse = b_label.lFalse;
            }
        }
    }


}
